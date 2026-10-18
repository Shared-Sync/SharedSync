package com.sharedsync.shared.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.storage.PresenceStorage;

import lombok.extern.slf4j.Slf4j;

/**
 * 캐시 → DB 동기화.
 *
 * <p>루트 하나의 동기화는 한 트랜잭션이고, 다음 순서로 진행한다.</p>
 * <ol>
 *   <li><b>유니크 제약 지연</b>: 앱이 DEFERRABLE 로 선언한 캐시 엔티티 테이블의 유니크 제약을 커밋 때 검사하게 한다
 *       ({@link UniqueConstraintDeferral}). 캐시의 최종 상태가 정상이면, 행을 쓰는 순서(캐시 순회 순서) 때문에 중간에
 *       같은 키가 두 줄이 되어도 실패하지 않는다. 지연 가능한 제약이 없으면 아무것도 하지 않는다.</li>
 *   <li><b>깨끗한 삭제</b>: 추적된 삭제 중 캐시에 살아 있는 DB 자식이 없는 행을 <b>깊은 repo부터</b> 먼저 지운다.
 *       같은 유니크 키로 지우고 다시 추가한 행(예: 같은 날짜를 지우고 새로 추가)의 INSERT 가 기존 행에 걸리지 않게
 *       하기 위해서다. 이런 행은 저장 단계와 서로 영향이 없어 순서만 앞선다.</li>
 *   <li><b>저장</b>: 루트부터 자식으로 내려가며 캐시 DTO를 DB에 반영한다(예전과 같은 순서).</li>
 *   <li><b>나머지 삭제</b>: 남은 추적 삭제를 <b>깊은 repo부터</b> 반영한다. 자식을 다른 부모로 옮긴 행이 여기 남는다.
 *       저장이 먼저 반영돼 있어야 옮긴 자식이 원래 부모와 함께 지워지지 않고, 자식부터 지워야 부모 삭제가 FK에
 *       걸리지 않는다.</li>
 *   <li><b>커밋 후 정리</b>: 이번에 처리한 삭제 추적 정리, 연속 실패 횟수 초기화, (퇴장 동기화면) 캐시 표시와 캐시
 *       삭제. 커밋이 성공했을 때만 실행된다. 롤백되면 캐시와 추적이 그대로 남아 다음 동기화가 다시 시도한다.</li>
 * </ol>
 *
 * <p>퇴장 동기화(keepCache=false) 도중 사용자가 돌아오면 트랜잭션을 rollback-only 로 표시하고 멈춘다.
 * 호출자에게는 예전처럼 정상 반환한다. 이 메서드는 트랜잭션을 직접 시작해야 한다 — 바깥 트랜잭션 안에서
 * 부르면 rollback-only 가 바깥 트랜잭션까지 번진다.</p>
 */
@Slf4j
public class CacheSyncService {
    private final List<AutoCacheRepository<?, ?, ?>> cacheRepositories;
    private final PresenceStorage presenceStorage;
    private final UniqueConstraintDeferral constraintDeferral;

    /** 유니크 제약을 미루지 않는다(예전 동작). */
    public CacheSyncService(List<AutoCacheRepository<?, ?, ?>> cacheRepositories, PresenceStorage presenceStorage) {
        this(cacheRepositories, presenceStorage, UniqueConstraintDeferral.disabled());
    }

    public CacheSyncService(List<AutoCacheRepository<?, ?, ?>> cacheRepositories, PresenceStorage presenceStorage,
            UniqueConstraintDeferral constraintDeferral) {
        this.cacheRepositories = cacheRepositories;
        this.presenceStorage = presenceStorage;
        this.constraintDeferral = constraintDeferral == null ? UniqueConstraintDeferral.disabled() : constraintDeferral;
    }

    /**
     * 캐시 삭제 예약 항목 (커밋 후 일괄 삭제용)
     */
    private static class CacheDeletionEntry {
        final AutoCacheRepository<?, ?, ?> repository;
        final Object id;

        CacheDeletionEntry(AutoCacheRepository<?, ?, ?> repository, Object id) {
            this.repository = repository;
            this.id = id;
        }
    }

    /** 동기화 한 번의 진행 상태. */
    private static class SyncRun {
        final String rootId;
        final boolean keepCache;
        final List<CacheDeletionEntry> deletionQueue = new ArrayList<>();
        boolean aborted;

        SyncRun(String rootId, boolean keepCache) {
            this.rootId = rootId;
            this.keepCache = keepCache;
        }
    }

    private record ProcessedDeletions(AutoCacheRepository<?, ?, ?> repository,
            AutoCacheRepository.DeletionBatch batch) {
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
        SyncRun run = new SyncRun(rootId, keepCache);
        List<AutoCacheRepository<?, ?, ?>> deepestFirst = childRepositoriesDeepestFirst();
        List<ProcessedDeletions> processed = new ArrayList<>();

        // 단계 0: 유니크 제약을 커밋 때 검사하게 한다. 이후의 쓰기부터 적용되므로 첫 쓰기보다 먼저 한다.
        constraintDeferral.deferWithinCurrentTransaction(rootId);

        // 단계 1: 깨끗한 삭제 (깊은 repo부터). 각 repo가 지운 뒤 바로 DB로 보내므로 저장 단계의 INSERT 보다 먼저 나간다.
        for (AutoCacheRepository<?, ?, ?> repository : deepestFirst) {
            processed.add(new ProcessedDeletions(repository, repository.deleteCleanTrackedForRoot(rootIdTyped)));
        }

        // 단계 2: 저장
        syncRecursively(rootRepository, rootIdTyped, run);
        if (run.aborted || userReturned(run)) {
            abort(run);
            return;
        }

        // 단계 3: 나머지 삭제 (깊은 repo부터)
        for (AutoCacheRepository<?, ?, ?> repository : deepestFirst) {
            processed.add(new ProcessedDeletions(repository, repository.deleteTrackedForRootUnchecked(rootIdTyped)));
        }

        // 단계 4: 커밋 후 정리 예약
        registerCleanup(run, processed);

        // keepCache=true면 캐시 삭제를 건너뜀 (주기적 동기화 시)
        if (keepCache) {
            log.info("[CacheSync] Periodic sync completed for rootId={}, cache retained.", rootId);
        }
    }

    // ==== 주인 없는 캐시 처리용 ====

    private AutoCacheRepository<?, ?, ?> rootRepository() {
        return cacheRepositories.stream()
                .filter(repo -> !repo.isParentIdFieldPresent())
                .findFirst()
                .orElse(null);
    }

    /** 캐시에 남아 있는 루트 ID 목록. */
    public List<String> cachedRootIds() {
        AutoCacheRepository<?, ?, ?> root = rootRepository();
        return root == null ? List.of() : root.cachedIds();
    }

    /**
     * 루트 행이 DB에 있는지. 앱의 조회 조건(소프트 삭제 등)이 적용된다. 조회가 실패하면 예외를 던진다.
     */
    public boolean rootExistsInDatabase(String rootId) {
        AutoCacheRepository<?, ?, ?> root = rootRepository();
        if (root == null) {
            return true;
        }
        return root.existsInDatabase(root.convertStringToId(rootId));
    }

    /**
     * 루트가 DB에서 사라진 캐시를 동기화 없이 지운다: 루트부터 자식까지의 캐시, 그 루트의 삭제 추적,
     * "온전히 채워진 상태" 표시, 연속 실패 횟수. 지울 대상(루트)이 없으니 저장할 것도 없다.
     */
    public void evictRootWithoutSync(String rootId) {
        AutoCacheRepository<?, ?, ?> root = rootRepository();
        if (root == null) {
            return;
        }
        Object rootIdTyped = root.convertStringToId(rootId);
        for (AutoCacheRepository<?, ?, ?> repository : childRepositoriesDeepestFirst()) {
            repository.discardTrackedDeletionsForRoot(rootIdTyped);
        }
        root.deleteCacheOnlyByIdUnchecked(rootIdTyped);
        presenceStorage.clearCacheReady(rootId);
        presenceStorage.clearSyncFailure(rootId);
    }

    private boolean userReturned(SyncRun run) {
        return !run.keepCache && presenceStorage.hasTracker(run.rootId);
    }

    /**
     * 중단: 지금까지의 쓰기를 커밋하지 않는다. 예전에는 그냥 return 해서 반쯤 반영된 상태가 커밋되고,
     * 커밋 후 캐시 삭제까지 실행돼 돌아온 사용자의 캐시가 통째로 지워졌다.
     */
    private void abort(SyncRun run) {
        run.aborted = true;
        log.info("[CacheSync] Aborting sync for rootId={} because user activity detected. Rolling back.", run.rootId);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (Exception e) {
                log.warn("[CacheSync] rollback-only 표시 실패 rootId={}: {}", run.rootId, e.getMessage());
            }
        }
    }

    private List<AutoCacheRepository<?, ?, ?>> childRepositoriesDeepestFirst() {
        return cacheRepositories.stream()
                .filter(AutoCacheRepository::isParentIdFieldPresent)
                .sorted(Comparator.comparingInt((AutoCacheRepository<?, ?, ?> r) -> r.hierarchyDepth()).reversed())
                .collect(Collectors.toList());
    }

    private void registerCleanup(SyncRun run, List<ProcessedDeletions> processed) {
        Runnable cleanup = () -> {
            // 추적 정리와 캐시 삭제는 Redis 작업뿐이다. 항목마다 따로 잡아 하나가 실패해도 나머지는 진행한다.
            // 추적 정리가 실패해도 다음 동기화가 같은 ID를 다시 처리하고, 이미 지워졌으면 건너뛴다(멱등).
            for (ProcessedDeletions p : processed) {
                try {
                    p.repository().clearTrackedDeletions(p.batch());
                } catch (Exception e) {
                    log.error("[CacheSync] 삭제 추적 정리 실패 rootId={} repo={}", run.rootId,
                            p.repository().getClass().getSimpleName(), e);
                }
            }
            // 커밋됐으면 이 방은 막혀 있지 않다.
            try {
                presenceStorage.clearSyncFailure(run.rootId);
            } catch (Exception e) {
                log.warn("[CacheSync] 연속 실패 횟수 초기화 실패 rootId={}: {}", run.rootId, e.getMessage());
            }
            if (run.keepCache) {
                return;
            }
            // 커밋과 이 시점 사이에 누가 들어왔으면 캐시를 남긴다. 커밋은 끝났으므로 DB와 캐시는 같다.
            // 트래커 확인과 "온전히 채워진 상태" 표시 삭제를 한 번에 한다. 따로 하면 그 사이 들어온 사용자가
            // 표시를 보고 다시 채우지 않기로 한 직후에 아래에서 캐시가 지워질 수 있다.
            try {
                if (!presenceStorage.clearCacheReadyIfNoTracker(run.rootId)) {
                    log.info("[CacheSync] User returned after commit. Keeping cache for rootId={}", run.rootId);
                    return;
                }
            } catch (Exception e) {
                log.warn("[CacheSync] 트래커 재확인 실패, 캐시를 남긴다 rootId={}: {}", run.rootId, e.getMessage());
                return;
            }
            log.info("[CacheSync] Transaction committed. Starting batch cache deletion for rootId={}", run.rootId);
            for (CacheDeletionEntry entry : run.deletionQueue) {
                try {
                    entry.repository.deleteCacheOnlyByIdUnchecked(entry.id);
                } catch (Exception e) {
                    log.error("[CacheSync] Failed to delete cache for id={} in repo={}", entry.id,
                            entry.repository.getClass().getSimpleName(), e);
                }
            }
        };

        // DB 트랜잭션이 아직 활성 상태라면 afterCommit 동기화 등록
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            // 트랜잭션이 없는 경우 (거의 없겠지만) 즉시 정리
            log.warn("[CacheSync] No active transaction. Cleaning up immediately for rootId={}", run.rootId);
            cleanup.run();
        }
    }

    private void syncRecursively(AutoCacheRepository<?, ?, ?> repository, Object id, SyncRun run) {
        if (repository == null || id == null || run.aborted) {
            return;
        }

        // 동기화 도중 유저가 접속하면 중단 (데이터 유실 방지 핵심 로직)
        // keepCache=true (주기적 동기화)일 때는 유저 접속 중이므로 이 체크를 건너뜀
        if (userReturned(run)) {
            log.info("[CacheSync] Aborting recursive sync for rootId={} because user activity detected", run.rootId);
            run.aborted = true;
            return;
        }

        Object dto = repository.findDtoByIdUnchecked(id);
        if (dto != null) {
            repository.syncToDatabaseByDtoUnchecked(dto);
        }

        // 자식 엔티티 저장 (루트부터 아래로). 삭제는 저장이 모두 끝난 뒤 단계 2에서 한다.
        Map<AutoCacheRepository<?, ?, ?>, List<?>> childDtos = cacheRepositories.stream()
                .filter(childRepo -> childRepo.hasParentRepository(repository))
                .collect(Collectors.toMap(childRepo -> childRepo,
                        childRepo -> childRepo.findDtoListByParentIdUnchecked(id)));

        for (Map.Entry<AutoCacheRepository<?, ?, ?>, List<?>> entry : childDtos.entrySet()) {
            if (run.aborted) {
                return;
            }
            AutoCacheRepository<?, ?, ?> childRepo = entry.getKey();

            List<?> dtos = entry.getValue();
            if (dtos == null) {
                dtos = List.of();
            }

            dtos.stream()
                    .filter(Objects::nonNull)
                    .forEach(childRepo::syncToDatabaseByDtoUnchecked);

            List<?> refreshed = childRepo.findDtoListByParentIdUnchecked(id);
            if (refreshed == null) {
                refreshed = List.of();
            }
            Set<Object> persistentIds = refreshed.stream()
                    .map(childRepo::extractIdUnchecked)
                    .filter(Objects::nonNull)
                    .filter(childRepo::isPersistentId)
                    .collect(Collectors.toSet());

            persistentIds.forEach(childId -> syncRecursively(childRepo, childId, run));
        }
        // 캐시 삭제를 바로 하지 않고, 삭제 대상 큐에 추가 (커밋 후 일괄 삭제)
        run.deletionQueue.add(new CacheDeletionEntry(repository, id));
    }
}
