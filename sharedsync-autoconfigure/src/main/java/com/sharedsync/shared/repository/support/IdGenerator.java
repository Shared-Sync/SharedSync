package com.sharedsync.shared.repository.support;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.id.IdPoolService;
import com.sharedsync.shared.repository.CacheStore;

import jakarta.persistence.EntityManager;

/**
 * ID 발급 협력자.
 *
 * <p>IdPool(숫자형 @CacheEntity) 모드면 DB 시퀀스에서 미리 할당받은 양수 ID를 반환하고,
 * 아니면 CacheStore DECR로 음수 임시 ID를 반환한다. {@link #init()} 은 스프링 빈 초기화 후
 * (호출자의 {@code @PostConstruct}) Pool 등록 + 현재 최대 ID 기준 시퀀스 리셋을 수행한다.</p>
 *
 * <p>IdPoolService/EntityManager/CacheStore 는 모두 {@link Supplier} 로 받아 지연 평가한다
 * (스프링 필드주입/빈 가용 시점 이후 안전하게 해석).</p>
 *
 * @param <T>   엔티티 타입
 * @param <ID>  ID 타입
 * @param <DTO> DTO 타입
 */
public final class IdGenerator<T, ID, DTO extends CacheDto<ID>> {

    private static final Logger log = LoggerFactory.getLogger(IdGenerator.class);

    private final CacheEntityMetadata<T, ID, DTO> metadata;
    private final Supplier<CacheStore<?>> cacheStore;
    private final Supplier<IdPoolService> idPoolService;
    private final Supplier<EntityManager> entityManager;

    public IdGenerator(CacheEntityMetadata<T, ID, DTO> metadata, Supplier<CacheStore<?>> cacheStore,
            Supplier<IdPoolService> idPoolService, Supplier<EntityManager> entityManager) {
        this.metadata = metadata;
        this.cacheStore = cacheStore;
        this.idPoolService = idPoolService;
        this.entityManager = entityManager;
    }

    /**
     * ID 생성: Pool 모드면 DB 시퀀스에서 미리 할당받은 양수 ID 반환,
     * 아니면 CacheStore DECR 방식으로 음수 임시 ID 반환.
     */
    public Long generateId() {
        if (metadata.isUseIdPool()) {
            try {
                return idPoolService.get().nextId(metadata.getSequenceName());
            } catch (Exception e) {
                log.warn("[AutoCacheRepository] ID Pool에서 ID 할당 실패, 음수 ID fallback: {}", e.getMessage());
            }
        }
        // fallback: 기존 음수 ID 방식
        String counterKey = "temporary:" + metadata.getCacheKeyPrefix() + ":counter";
        return cacheStore.get().decrement(counterKey);
    }

    /** 스프링 빈 초기화 후 ID Pool 등록 및 초기 할당(Pool 모드가 아니면 no-op). */
    public void init() {
        if (!metadata.isUseIdPool()) {
            return;
        }
        String sequenceName = metadata.getSequenceName();
        int allocationSize = metadata.getAllocationSize();
        try {
            IdPoolService pool = idPoolService.get();
            pool.registerPool(sequenceName, allocationSize);

            // Redis에 기존 Pool이 있으면 시퀀스 리셋 없이 Redis에서 복원
            // Redis에 없을 때만 시퀀스를 현재 최대 ID로 리셋 후 새로 할당
            if (!pool.isRedisPoolIntact()) {
                try {
                    String entityName = metadata.getEntityClass().getSimpleName();
                    String idFieldName = metadata.getEntityIdField().getName();
                    Object result = entityManager.get().createQuery(
                            "SELECT MAX(e." + idFieldName + ") FROM " + entityName + " e")
                            .getSingleResult();
                    Long maxId = (result != null) ? ((Number) result).longValue() : null;
                    if (maxId != null) {
                        pool.resetSequenceToMaxId(sequenceName, maxId);
                        log.info("[AutoCacheRepository] Sequence '{}' reset to current max ID={} for entity={}",
                                sequenceName, maxId, entityName);
                    }
                } catch (Exception e) {
                    log.warn("[AutoCacheRepository] 시퀀스 리셋 실패 (무시하고 계속): {}", e.getMessage());
                }
            } else {
                log.info("[AutoCacheRepository] Redis pool exists for '{}', skipping sequence reset",
                        sequenceName);
            }

            pool.initializePool(sequenceName);
            log.info("[AutoCacheRepository] ID Pool initialized: entity={}, sequence={}, allocationSize={}",
                    metadata.getEntityClass().getSimpleName(), sequenceName, allocationSize);
        } catch (Exception e) {
            log.warn("[AutoCacheRepository] ID Pool 초기화 실패 (fallback to negative ID): {}", e.getMessage());
        }
    }
}
