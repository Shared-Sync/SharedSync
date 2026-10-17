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
            if (allRoomIds == null || allRoomIds.isEmpty()) {
                return;
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
                    cacheSyncService.syncToDatabase(rootId, true); // keepCache=true
                    consecutiveFailures.remove(rootId);
                } catch (Exception e) {
                    log.error("[PeriodicSync] Failed to sync rootId={}: {}", rootId, e.getMessage());
                    recordFailure(rootId);
                } finally {
                    presenceStorage.releaseSyncLock(rootId, token.get());
                    warnIfSlow(rootId, started);
                }
            }
            // 사용자가 모두 떠난 방의 실패 기록은 남겨둘 이유가 없다.
            consecutiveFailures.keySet().retainAll(allRoomIds);
        } catch (Exception e) {
            log.error("[PeriodicSync] Unexpected error during periodic sync: {}", e.getMessage());
        }
    }

    /** 방별 연속 실패 횟수(이 인스턴스 기준). 관측용이며 동작은 바꾸지 않는다. */
    private final java.util.Map<String, Integer> consecutiveFailures = new java.util.concurrent.ConcurrentHashMap<>();

    private SyncMetrics metrics = SyncMetrics.NOOP;

    public void setMetrics(SyncMetrics metrics) {
        this.metrics = metrics == null ? SyncMetrics.NOOP : metrics;
    }

    private void recordFailure(String rootId) {
        metrics.syncFailure("periodic");
        int count = consecutiveFailures.merge(rootId, 1, Integer::sum);
        int threshold = presenceProperties.getSyncFailureAlertThreshold();
        if (threshold > 0 && count >= threshold) {
            // 이 방의 변경은 계속 DB에 들어가지 못하고 캐시에만 쌓이는 중이다. 삭제 추적은 이 방 안에 머물러
            // 다른 방으로 번지지 않지만, 원인(위의 실패 로그)을 풀어야 이 방이 저장된다.
            log.error("[PeriodicSync] rootId={} 동기화가 {}회 연속 실패했다. 이 방의 변경이 DB에 반영되지 않고 있다.",
                    rootId, count);
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
