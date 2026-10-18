package com.sharedsync.shared.sync;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.sharedsync.shared.id.IdPoolService;
import com.sharedsync.shared.metrics.SyncMetrics;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.storage.PresenceStorage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 활성 Room의 캐시를 주기적으로 DB에 동기화하는 스케줄러.
 * <p>
 * 디스커넥트 시에만 동기화하던 방식에 추가로, 연결 중에도 주기적으로
 * 캐시 데이터를 DB에 백업합니다. 이때 캐시는 삭제되지 않습니다 (keepCache=true).
 */
@RequiredArgsConstructor
@Slf4j
public class PeriodicSyncScheduler {

    private final CacheSyncService cacheSyncService;
    private final PresenceStorage presenceStorage;
    private final SharedSyncPresenceProperties presenceProperties;
    private final IdPoolService idPoolService;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        if (!presenceProperties.isPeriodicSyncEnabled()
                || presenceProperties.getPeriodicSyncInterval() <= 0) {
            log.info("[PeriodicSync] Periodic sync is disabled.");
            return;
        }

        long intervalSeconds = presenceProperties.getPeriodicSyncInterval();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "periodic-sync-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(
                this::syncAllActiveRooms,
                intervalSeconds, // 초기 지연
                intervalSeconds, // 반복 간격
                TimeUnit.SECONDS);

        log.info("[PeriodicSync] Scheduled periodic sync every {} seconds.", intervalSeconds);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[PeriodicSync] Scheduler shut down.");
        }
    }

    /**
     * 활성 사용자가 있는 모든 Room에 대해 주기적 동기화를 수행합니다.
     * keepCache=true로 호출하여 캐시는 유지하고 DB만 업데이트합니다.
     */
    private void syncAllActiveRooms() {
        try {
            // Redis Pool이 비어있으면 즉시 비동기 리필 트리거
            if (!idPoolService.isRedisPoolIntact()) {
                log.info("[PeriodicSync] Redis IdPool data missing. Triggering refill now.");
                idPoolService.refillEmptyPools();
            }

            Set<String> allRoomIds = presenceStorage.getAllRoomIds();
            if (allRoomIds == null) {
                allRoomIds = Set.of();
            }

            // 활성 사용자가 있는 Room만 필터
            for (String rootId : allRoomIds) {
                if (!presenceStorage.hasTracker(rootId)) {
                    continue; // 사용자가 없는 Room은 건너뜀
                }

                // 로딩 중이면 건너뜀
                if (presenceStorage.isLoading(rootId)) {
                    log.debug("[PeriodicSync] Skipping rootId={}, cache loading in progress.", rootId);
                    continue;
                }

                // 이미 동기화 중이면 건너뜀. 예전에는 isSyncing 만 보고 잠금 없이 돌아서, 다른 파드의
                // 주기 동기화나 퇴장 동기화와 같은 방을 동시에 쓸 수 있었다.
                java.util.Optional<String> token = presenceStorage.tryAcquireSyncLock(rootId,
                        presenceProperties.getSyncLockTtlMs());
                if (token.isEmpty()) {
                    log.debug("[PeriodicSync] Skipping rootId={}, already syncing.", rootId);
                    continue;
                }

                long started = System.currentTimeMillis();
                try {
                    log.debug("[PeriodicSync] Syncing rootId={}", rootId);
                    // 성공하면 커밋 후 정리가 연속 실패 횟수를 지운다.
                    cacheSyncService.syncToDatabase(rootId, true); // keepCache=true
                } catch (Exception e) {
                    log.error("[PeriodicSync] Failed to sync rootId={}: {}", rootId, e.getMessage());
                    recordFailure(rootId, e, "periodic");
                } finally {
                    presenceStorage.releaseSyncLock(rootId, token.get());
                    warnIfSlow(rootId, started);
                }
            }
        } catch (Exception e) {
            log.error("[PeriodicSync] Unexpected error during periodic sync: {}", e.getMessage());
        }

        try {
            syncOrphanCaches();
        } catch (Exception e) {
            log.error("[PeriodicSync] Unexpected error during orphan cache sync: {}", e.getMessage());
        }
    }

    private SyncMetrics metrics = SyncMetrics.NOOP;

    public void setMetrics(SyncMetrics metrics) {
        this.metrics = metrics == null ? SyncMetrics.NOOP : metrics;
    }

    /**
     * 연속 실패 횟수를 저장소에 센다(파드끼리 공유 — 다른 파드의 재입장이 "막힌 방"인지 판단할 때 본다).
     * DB에 닿지 못한 실패는 그 방이 막힌 게 아니므로 세지 않는다. 알림·메트릭은 그대로 남긴다.
     */
    private void recordFailure(String rootId, Exception error, String source) {
        metrics.syncFailure(source);
        if (SyncFailureClassifier.isConnectionFailure(error)) {
            return;
        }
        int count;
        try {
            count = presenceStorage.incrementSyncFailure(rootId,
                    TimeUnit.SECONDS.toMillis(presenceProperties.getSyncFailureCountTtlSeconds()));
        } catch (Exception e) {
            log.warn("[PeriodicSync] 연속 실패 횟수 기록 실패 rootId={}: {}", rootId, e.getMessage());
            return;
        }
        int threshold = presenceProperties.getSyncFailureAlertThreshold();
        if (threshold > 0 && count >= threshold) {
            // 이 방의 변경은 계속 DB에 들어가지 못하고 캐시에만 쌓이는 중이다. 삭제 추적은 이 방 안에 머물러
            // 다른 방으로 번지지 않지만, 원인(위의 실패 로그)을 풀어야 이 방이 저장된다.
            log.error("[PeriodicSync] rootId={} 동기화가 {}회 연속 실패했다. 이 방의 변경이 DB에 반영되지 않고 있다.",
                    rootId, count);
        }
    }

    private boolean isBlocked(String rootId) {
        int threshold = presenceProperties.getSyncFailureAlertThreshold();
        return threshold > 0 && presenceStorage.getSyncFailureCount(rootId) >= threshold;
    }

    // ==== 주인 없는 캐시 ====

    /** 한 주기에 처리하는 주인 없는 캐시 수 상한. */
    private static final int ORPHAN_MAX_PER_RUN = 20;

    /** 주인 없는 캐시 후보로 처음 본 시각(이 인스턴스 기준). 유예 시간을 재는 데 쓴다. */
    private final java.util.Map<String, Long> orphanFirstSeen = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 접속자도, 예약된 퇴장 동기화도, 진행 중인 동기화도 없이 남은 캐시를 저장한다.
     *
     * <p>이런 캐시는 마지막 동기화가 재시도 끝에 포기했거나 실패했을 때 남는다. 예전에는 다음 입장 때 DB 값으로
     * 덮여 편집이 사라졌고, 아무도 안 들어오면 영영 DB에 들어가지 않았다. 적재를 끝까지 성공했다는 표시가 있는
     * 캐시만 대상으로 하므로, 이 기능을 배포하기 전부터 남아 있던 캐시는 건드리지 않는다. 정상 경로에서는 퇴장
     * 동기화가 캐시와 표시를 지우므로 대상이 생기지 않는다.</p>
     *
     * <p>막힌 방(연속 실패가 알림 기준 이상)은 건너뛴다. 같은 실패만 반복하므로, 재입장 때 예전처럼 DB에서 다시
     * 채우게 둔다. 루트가 DB에서 사라진 캐시(예: 소프트 삭제된 플랜)는 저장하지 않고 지운다.</p>
     */
    private void syncOrphanCaches() {
        if (!presenceProperties.isOrphanSyncEnabled()) {
            return;
        }
        java.util.List<String> rootIds = cacheSyncService.cachedRootIds();
        if (rootIds == null || rootIds.isEmpty()) {
            orphanFirstSeen.clear();
            return;
        }
        long now = System.currentTimeMillis();
        long graceMillis = TimeUnit.SECONDS.toMillis(Math.max(0L, presenceProperties.getOrphanGraceSeconds()));
        java.util.Set<String> candidates = new java.util.HashSet<>();
        int processed = 0;
        for (String rootId : rootIds) {
            if (!isOrphanCandidate(rootId)) {
                continue;
            }
            candidates.add(rootId);
            long firstSeen = orphanFirstSeen.computeIfAbsent(rootId, k -> now);
            if (now - firstSeen < graceMillis || processed >= ORPHAN_MAX_PER_RUN) {
                continue;
            }
            processed++;
            handleOrphan(rootId);
        }
        orphanFirstSeen.keySet().retainAll(candidates);
    }

    private boolean isOrphanCandidate(String rootId) {
        try {
            return presenceStorage.isCacheReady(rootId)
                    && !presenceStorage.hasTracker(rootId)
                    && !presenceStorage.hasPendingSync(rootId)
                    && !presenceStorage.isSyncing(rootId)
                    && !presenceStorage.isLoading(rootId)
                    && !isBlocked(rootId);
        } catch (Exception e) {
            log.warn("[PeriodicSync] 주인 없는 캐시 확인 실패 rootId={}: {}", rootId, e.getMessage());
            return false;
        }
    }

    private void handleOrphan(String rootId) {
        java.util.Optional<String> token = presenceStorage.tryAcquireSyncLock(rootId,
                presenceProperties.getSyncLockTtlMs());
        if (token.isEmpty()) {
            return;
        }
        long started = System.currentTimeMillis();
        try {
            // 잠금을 잡은 뒤 다시 본다. 그 사이 누가 들어왔거나 퇴장 동기화가 예약됐을 수 있다.
            if (presenceStorage.hasTracker(rootId) || presenceStorage.hasPendingSync(rootId)
                    || !presenceStorage.isCacheReady(rootId)) {
                return;
            }
            // 조회가 실패하면 예외 → 아무것도 지우지 않고 다음 주기로 넘긴다.
            if (!cacheSyncService.rootExistsInDatabase(rootId)) {
                log.info("[PeriodicSync] rootId={} 의 루트가 DB에 없어(삭제됨) 남은 캐시를 저장하지 않고 지운다.", rootId);
                cacheSyncService.evictRootWithoutSync(rootId);
                orphanFirstSeen.remove(rootId);
                return;
            }
            log.info("[PeriodicSync] 주인 없이 남은 캐시를 저장한다 rootId={}", rootId);
            cacheSyncService.syncToDatabase(rootId, false);
            orphanFirstSeen.remove(rootId);
        } catch (Exception e) {
            log.error("[PeriodicSync] 주인 없는 캐시 저장 실패 rootId={}: {}", rootId, e.getMessage());
            recordFailure(rootId, e, "orphan");
        } finally {
            presenceStorage.releaseSyncLock(rootId, token.get());
            warnIfSlow(rootId, started);
        }
    }

    private void warnIfSlow(String rootId, long startedMillis) {
        long elapsed = System.currentTimeMillis() - startedMillis;
        long threshold = presenceProperties.getSlowSyncWarnMs();
        if (threshold > 0 && elapsed > threshold) {
            log.warn("[PeriodicSync] rootId={} 동기화가 {}ms 걸렸다. 잠금 유지 시간({}ms)에 가까워지면 다른 동기화와 겹칠 수 있다.",
                    rootId, elapsed, presenceProperties.getSyncLockTtlMs());
        }
    }
}
