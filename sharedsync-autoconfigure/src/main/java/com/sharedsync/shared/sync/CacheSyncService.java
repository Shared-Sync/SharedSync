package com.sharedsync.shared.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.storage.PresenceStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheSyncService {
    private final List<AutoCacheRepository<?, ?, ?>> cacheRepositories;
    private final PresenceStorage presenceStorage;

    /**
     * 캐시 삭제 예약 항목 (Phase 2에서 일괄 삭제용)
     */
    private static class CacheDeletionEntry {
        final AutoCacheRepository<?, ?, ?> repository;
        final Object id;

        CacheDeletionEntry(AutoCacheRepository<?, ?, ?> repository, Object id) {
            this.repository = repository;
            this.id = id;
        }
    }

    @Transactional
    public void syncToDatabase(String rootId) {
        syncToDatabase(rootId, false);
    }

    /**
     * 캐시 데이터를 DB에 동기화합니다.
     * 
     * @param rootId    루트 엔티티 ID
     * @param keepCache true면 DB 동기화만 수행하고 캐시는 유지 (주기적 동기화용),
     *                  false면 동기화 후 캐시 삭제 (디스커넥트 시)
     */
    @Transactional
    public void syncToDatabase(String rootId, boolean keepCache) {
        log.info("[CacheSync] [TRACE-F5] Request received for rootId={}, keepCache={}", rootId, keepCache);

        // keepCache=false (디스커넥트 동기화)일 때만 Active tracker 체크
        // keepCache=true (주기적 동기화)는 유저 접속 중 DB 백업이 목적이므로 체크 건너뜀
        if (!keepCache && presenceStorage.hasTracker(rootId)) {
            log.info("[CacheSync] [TRACE-F5] Sync aborted for rootId={}: Active tracker detected. (User returned)",
                    rootId);
            return;
        }

        // 2. 캐시 로딩(Initialize) 중이면 중단 (불완전 캐시 상태)
        // CacheInitializer에서 로딩 시작 시 설정하고 완료 시 해제함
        if (presenceStorage.isLoading(rootId)) {
            log.warn("[CacheSync] [TRACE-F5] Sync aborted for rootId={}: Cache initialization in progress.", rootId);
            return;
        }

        AutoCacheRepository<?, ?, ?> rootRepository = cacheRepositories.stream()
                .filter(repo -> !repo.isParentIdFieldPresent())
                .findFirst()
                .orElse(null);

        if (rootRepository == null) {
            log.warn("[CacheSync] No root repository found for sync");
            return;
        }

        Object rootIdTyped = rootRepository.convertStringToId(rootId);
        List<CacheDeletionEntry> deletionQueue = new ArrayList<>();

        syncRecursively(rootRepository, rootIdTyped, deletionQueue, rootId, keepCache);

        // keepCache=true면 캐시 삭제를 건너뜀 (주기적 동기화 시)
        if (keepCache) {
            log.info("[CacheSync] Periodic sync completed for rootId={}, cache retained.", rootId);
            return;
        }

        // Phase 2: 캐시 일괄 삭제 (트랜잭션 커밋 후 실행)
        // DB 트랜잭션이 아직 활성 상태라면 afterCommit 동기화 등록
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("[CacheSync] Transaction committed. Starting batch cache deletion for rootId={}", rootId);
                    for (CacheDeletionEntry entry : deletionQueue) {
                        try {
                            entry.repository.deleteCacheOnlyByIdUnchecked(entry.id);
                        } catch (Exception e) {
                            log.error("[CacheSync] Failed to delete cache for id={} in repo={}", entry.id,
                                    entry.repository.getClass().getSimpleName(), e);
                        }
                    }
                }
            });
        } else {
            // 트랜잭션이 없는 경우 (거의 없겠지만) 즉시 삭제
            log.warn("[CacheSync] No active transaction. Deleting cache immediately for rootId={}", rootId);
            for (CacheDeletionEntry entry : deletionQueue) {
                entry.repository.deleteCacheOnlyByIdUnchecked(entry.id);
            }
        }
    }

    private void syncRecursively(AutoCacheRepository<?, ?, ?> repository, Object id,
            List<CacheDeletionEntry> deletionQueue, String rootId, boolean keepCache) {
        if (repository == null || id == null) {
            return;
        }

        // 동기화 도중 유저가 접속하면 중단 (데이터 유실 방지 핵심 로직)
        // keepCache=true (주기적 동기화)일 때는 유저 접속 중이므로 이 체크를 건너뜀
        if (!keepCache && presenceStorage.hasTracker(rootId)) {
            log.info("[CacheSync] Aborting recursive sync for rootId={} because user activity detected", rootId);
            return;
        }

        Object dto = repository.findDtoByIdUnchecked(id);
        if (dto != null) {
            repository.syncToDatabaseByDtoUnchecked(dto);
        }

        // Phase 1: 자식 엔티티 동기화 (Leaf부터 상향식으로 진행됨)
        Map<AutoCacheRepository<?, ?, ?>, List<?>> childDtos = cacheRepositories.stream()
                .filter(childRepo -> childRepo.hasParentRepository(repository))
                .collect(Collectors.toMap(childRepo -> childRepo,
                        childRepo -> childRepo.findDtoListByParentIdUnchecked(id)));

        for (Map.Entry<AutoCacheRepository<?, ?, ?>, List<?>> entry : childDtos.entrySet()) {
            AutoCacheRepository<?, ?, ?> childRepo = entry.getKey();

            List<?> dtos = entry.getValue();
            if (dtos == null) {
                dtos = List.of();
            }

            dtos.stream()
                    .filter(Objects::nonNull)
                    .forEach(childRepo::syncToDatabaseByDtoUnchecked);

            // 삭제 추적 Set 기반으로 DB에서 삭제 (비교 방식보다 안정적)
            childRepo.deleteEntitiesByDeletedSet();

            List<?> refreshed = childRepo.findDtoListByParentIdUnchecked(id);
            if (refreshed == null) {
                refreshed = List.of();
            }
            Set<Object> persistentIds = refreshed.stream()
                    .map(childRepo::extractIdUnchecked)
                    .filter(Objects::nonNull)
                    .filter(childRepo::isPersistentId)
                    .collect(Collectors.toSet());

            persistentIds.forEach(childId -> syncRecursively(childRepo, childId, deletionQueue, rootId, keepCache));
        }
        // 캐시 삭제를 바로 하지 않고, 삭제 대상 큐에 추가 (Phase 2에서 일괄 삭제)
        deletionQueue.add(new CacheDeletionEntry(repository, id));
    }
}
