package com.sharedsync.shared.sync;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.sharedsync.shared.id.IdPoolService;
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
@Component
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

                // 이미 동기화 중이면 건너뜀
                if (presenceStorage.isSyncing(rootId)) {
                    log.debug("[PeriodicSync] Skipping rootId={}, already syncing.", rootId);
                    continue;
                }

                // 로딩 중이면 건너뜀
                if (presenceStorage.isLoading(rootId)) {
                    log.debug("[PeriodicSync] Skipping rootId={}, cache loading in progress.", rootId);
                    continue;
                }

                try {
                    log.debug("[PeriodicSync] Syncing rootId={}", rootId);
                    cacheSyncService.syncToDatabase(rootId, true); // keepCache=true
                } catch (Exception e) {
                    log.error("[PeriodicSync] Failed to sync rootId={}: {}", rootId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[PeriodicSync] Unexpected error during periodic sync: {}", e.getMessage());
        }
    }
}
