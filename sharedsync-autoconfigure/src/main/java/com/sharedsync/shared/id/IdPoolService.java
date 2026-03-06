package com.sharedsync.shared.id;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * DB 시퀀스에서 ID를 배치로 미리 할당하고, Redis Set에 저장하여 관리하는 서비스.
 * <p>
 * Pool은 Redis에만 존재하며, 인메모리 상태를 유지하지 않습니다.
 * - SPOP: 원자적으로 ID를 꺼내 사용
 * - SADD: 리필 시 ID를 추가
 * - SCARD: 현재 Pool 크기 확인
 * <p>
 * Redis가 비어있거나 사용할 수 없는 경우 DB 시퀀스에서 새로 할당합니다.
 */
@Service
@Slf4j
public class IdPoolService {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    @Qualifier("presenceRedis")
    private RedisTemplate<String, Object> redisTemplate;

    private static final String REDIS_KEY_PREFIX = "IDPOOL:";

    /**
     * 시퀀스 이름 → IdPool(설정 + 리필 플래그) 맵핑
     */
    private final Map<String, IdPool> pools = new ConcurrentHashMap<>();

    /**
     * 시퀀스 이름에 대한 Pool을 등록합니다.
     * AutoCacheRepository 초기화 시 호출됩니다.
     */
    public void registerPool(String sequenceName, int allocationSize) {
        pools.computeIfAbsent(sequenceName, key -> {
            IdPool pool = new IdPool(key, allocationSize);
            log.info("[IdPoolService] Pool registered: sequenceName={}, allocationSize={}", key, allocationSize);
            return pool;
        });
    }

    /**
     * 지정된 시퀀스에서 다음 ID를 반환합니다.
     * Redis Set에서 SPOP으로 원자적으로 꺼냅니다.
     * Pool이 비어있으면 동기적으로 DB에서 할당합니다.
     *
     * @param sequenceName DB 시퀀스 이름
     * @return 다음 유효한 양수 ID
     */
    public Long nextId(String sequenceName) {
        IdPool pool = pools.get(sequenceName);
        if (pool == null) {
            throw new IllegalStateException("[IdPoolService] 등록되지 않은 시퀀스: " + sequenceName);
        }

        // Redis SPOP으로 원자적으로 ID 꺼내기
        Long id = popIdFromRedis(sequenceName);
        if (id != null) {
            // 비동기 리필 트리거 (임계값 이하일 때)
            long remaining = getRedisPoolSize(sequenceName);
            if (pool.needsRefill(remaining) && pool.tryStartRefill()) {
                CompletableFuture.runAsync(() -> {
                    try {
                        refillPool(pool);
                    } finally {
                        pool.finishRefill();
                    }
                });
            }
            return id;
        }

        // Redis Pool이 비어있음 → 동기적으로 DB에서 리필 후 재시도
        log.warn("[IdPoolService] Pool exhausted for '{}'. Refilling synchronously...", sequenceName);
        refillPoolSync(pool);
        id = popIdFromRedis(sequenceName);
        if (id == null) {
            throw new IllegalStateException("[IdPoolService] Pool 리필 후에도 ID를 할당할 수 없습니다: " + sequenceName);
        }
        return id;
    }

    /**
     * Pool에 대한 초기 ID 할당을 수행합니다.
     * Redis에 기존 Pool이 있으면 DB 시퀀스를 호출하지 않습니다.
     * 애플리케이션 시작 시 호출됩니다.
     */
    public void initializePool(String sequenceName) {
        IdPool pool = pools.get(sequenceName);
        if (pool == null) {
            log.warn("[IdPoolService] 초기화 시 등록되지 않은 시퀀스: {}", sequenceName);
            return;
        }

        // Redis에 기존 Pool이 있으면 초기화 건너뜀 (시퀀스 낭비 방지)
        long existingSize = getRedisPoolSize(sequenceName);
        if (existingSize > 0) {
            log.info("[IdPoolService] Pool already exists in Redis: sequenceName={}, size={}",
                    sequenceName, existingSize);
            return;
        }

        // Redis에 없으면 DB 시퀀스에서 새로 할당
        refillPoolSync(pool);
    }

    // ==== Redis 직접 조작 메서드 ====

    /**
     * Redis Set에서 ID를 원자적으로 꺼냅니다 (SPOP).
     */
    private Long popIdFromRedis(String sequenceName) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String redisKey = REDIS_KEY_PREFIX + sequenceName;
            Object popped = redisTemplate.opsForSet().pop(redisKey);
            if (popped == null) {
                return null;
            }
            return Long.parseLong(String.valueOf(popped));
        } catch (Exception e) {
            log.warn("[IdPoolService] Redis SPOP failed for '{}': {}", sequenceName, e.getMessage());
            return null;
        }
    }

    /**
     * Redis Pool의 현재 크기를 반환합니다 (SCARD).
     */
    private long getRedisPoolSize(String sequenceName) {
        if (redisTemplate == null) {
            return 0;
        }
        try {
            String redisKey = REDIS_KEY_PREFIX + sequenceName;
            Long size = redisTemplate.opsForSet().size(redisKey);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.debug("[IdPoolService] Redis SCARD failed for '{}': {}", sequenceName, e.getMessage());
            return 0;
        }
    }

    /**
     * Redis Set에 ID 목록을 추가합니다 (SADD).
     */
    private void saveIdsToRedis(String sequenceName, List<Long> ids) {
        if (redisTemplate == null || ids == null || ids.isEmpty()) {
            return;
        }
        try {
            String redisKey = REDIS_KEY_PREFIX + sequenceName;
            Object[] values = ids.stream().map(String::valueOf).toArray();
            redisTemplate.opsForSet().add(redisKey, values);
        } catch (Exception e) {
            log.warn("[IdPoolService] Redis SADD failed for '{}': {}", sequenceName, e.getMessage());
        }
    }

    // ==== 리필 메서드 ====

    /**
     * DB 시퀀스에서 ID를 배치로 가져와 Redis에 추가합니다 (비동기용).
     */
    private void refillPool(IdPool pool) {
        try {
            List<Long> ids = fetchIdsFromSequence(pool.getSequenceName(), pool.getAllocationSize());
            saveIdsToRedis(pool.getSequenceName(), ids);
            log.info("[IdPoolService] Pool refilled asynchronously: sequenceName={}, count={}, redisPoolSize={}",
                    pool.getSequenceName(), ids.size(), getRedisPoolSize(pool.getSequenceName()));
        } catch (Exception e) {
            log.error("[IdPoolService] Async pool refill failed for '{}': {}", pool.getSequenceName(), e.getMessage());
        }
    }

    /**
     * DB 시퀀스에서 ID를 배치로 가져와 Redis에 추가합니다 (동기).
     */
    private void refillPoolSync(IdPool pool) {
        List<Long> ids = fetchIdsFromSequence(pool.getSequenceName(), pool.getAllocationSize());
        saveIdsToRedis(pool.getSequenceName(), ids);
        log.info("[IdPoolService] Pool refilled synchronously: sequenceName={}, count={}, redisPoolSize={}",
                pool.getSequenceName(), ids.size(), getRedisPoolSize(pool.getSequenceName()));
    }

    // ==== DB 시퀀스 메서드 ====

    /**
     * PostgreSQL 시퀀스에서 N개의 ID를 가져옵니다.
     */
    private List<Long> fetchIdsFromSequence(String sequenceName, int count) {
        if (entityManager == null) {
            throw new IllegalStateException("[IdPoolService] EntityManager is not available");
        }

        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object result = entityManager
                    .createNativeQuery("SELECT nextval(:seqName)")
                    .setParameter("seqName", sequenceName)
                    .getSingleResult();
            ids.add(((Number) result).longValue());
        }

        log.debug("[IdPoolService] Fetched {} IDs from sequence '{}': range [{} ~ {}]",
                count, sequenceName,
                ids.isEmpty() ? "N/A" : ids.get(0),
                ids.isEmpty() ? "N/A" : ids.get(ids.size() - 1));

        return ids;
    }

    // ==== 상태 조회/관리 메서드 ====

    /**
     * Redis의 Pool 데이터 존재 여부를 확인합니다.
     */
    public boolean isRedisPoolIntact() {
        if (redisTemplate == null) {
            return false;
        }
        if (pools.isEmpty()) {
            return true; // 등록된 Pool이 없으면 체크할 것이 없으므로 정상으로 간주
        }
        for (String sequenceName : pools.keySet()) {
            if (getRedisPoolSize(sequenceName) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 비어있는 Pool을 모두 비동기로 리필합니다.
     * PeriodicSyncScheduler에서 주기적으로 호출됩니다.
     */
    public void refillEmptyPools() {
        for (IdPool pool : pools.values()) {
            long size = getRedisPoolSize(pool.getSequenceName());
            if (size == 0 && pool.tryStartRefill()) {
                log.info("[IdPoolService] Pool empty for '{}', triggering async refill.", pool.getSequenceName());
                CompletableFuture.runAsync(() -> {
                    try {
                        refillPool(pool);
                    } finally {
                        pool.finishRefill();
                    }
                });
            }
        }
    }

    /**
     * 등록된 모든 Pool의 Redis 크기를 반환합니다 (디버깅/모니터링용).
     */
    public Map<String, Long> getPoolStatus() {
        Map<String, Long> status = new ConcurrentHashMap<>();
        pools.forEach((name, pool) -> status.put(name, getRedisPoolSize(name)));
        return status;
    }

    /**
     * DB 시퀀스를 현재 테이블의 최대 ID 값으로 리셋합니다.
     * 서버 재시작 시 사용하지 않은 Pool ID로 인해 시퀀스가 계속 증가하는 문제를 방지합니다.
     *
     * @param sequenceName DB 시퀀스 이름
     * @param maxId        현재 테이블에 존재하는 최대 ID 값
     */
    public void resetSequenceToMaxId(String sequenceName, long maxId) {
        if (entityManager == null) {
            log.warn("[IdPoolService] EntityManager is not available, cannot reset sequence");
            return;
        }
        try {
            entityManager
                    .createNativeQuery("SELECT setval(:seqName, :maxId)")
                    .setParameter("seqName", sequenceName)
                    .setParameter("maxId", maxId)
                    .getSingleResult();
            log.info("[IdPoolService] Sequence '{}' reset to maxId={}", sequenceName, maxId);
        } catch (Exception e) {
            log.warn("[IdPoolService] Failed to reset sequence '{}': {}", sequenceName, e.getMessage());
        }
    }
}
