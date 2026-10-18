package com.sharedsync.shared.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

import com.sharedsync.shared.history.HistoryService;
import com.sharedsync.shared.presence.core.PresenceBroadcaster;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.presence.core.UserProvider;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.storage.InMemoryPresenceStorage;
import com.sharedsync.shared.sync.CacheSyncService;
import com.sharedsync.shared.transport.WebSocketSessionRegistry;

/** 묶음 B: 재입장 시 남은 캐시 재사용(B1), 막힌 방 보험, 예약 표시, 실패 횟수, 종료 정리(B2). */
class PresenceRejoinTest {

    private static final String ROOT = "room-1";

    private final InMemoryPresenceStorage storage = new InMemoryPresenceStorage();
    private final CacheInitializer initializer = mock(CacheInitializer.class);
    private final CacheSyncService sync = mock(CacheSyncService.class);
    private final SharedSyncPresenceProperties props = new SharedSyncPresenceProperties();
    private PresenceSessionManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.cleanup();
        }
    }

    @SuppressWarnings("unchecked")
    private PresenceSessionManager manager() {
        props.setSyncWaitAttempts(1);
        props.setSyncWaitIntervalMs(1);
        props.setBroadcastDelay(0);
        props.setSyncLockRetryIntervalMs(20);
        PresenceRootResolver resolver = mock(PresenceRootResolver.class);
        when(resolver.getChannel()).thenReturn("ch");
        manager = new PresenceSessionManager(storage, mock(PresenceBroadcaster.class), mock(UserProvider.class),
                initializer, sync, mock(HistoryService.class), resolver, new SharedSyncAuthProperties(), props,
                new SharedSyncWebSocketProperties(),
                (ObjectProvider<WebSocketSessionRegistry>) mock(ObjectProvider.class));
        return manager;
    }

    // ===== B1 =====

    @Test
    void U1_firstUserReusesReadyCache() {
        PresenceSessionManager m = manager();
        storage.markCacheReady(ROOT);
        when(initializer.hasCachedRoot(ROOT)).thenReturn(true);

        m.handleSubscribe(ROOT, "u1", "s1");

        verify(initializer, never()).initializeHierarchy(ROOT);
    }

    @Test
    void U2_readyMarkWithoutRootDtoReloads() {
        PresenceSessionManager m = manager();
        storage.markCacheReady(ROOT);
        when(initializer.hasCachedRoot(ROOT)).thenReturn(false);

        m.handleSubscribe(ROOT, "u1", "s1");

        verify(initializer).initializeHierarchy(ROOT);
    }

    @Test
    void noReadyMarkReloadsAsBefore() {
        PresenceSessionManager m = manager();
        when(initializer.hasCachedRoot(ROOT)).thenReturn(true);

        m.handleSubscribe(ROOT, "u1", "s1");

        verify(initializer).initializeHierarchy(ROOT);
    }

    @Test
    void U8_blockedRoomReloadsFromDatabase() {
        PresenceSessionManager m = manager();
        storage.markCacheReady(ROOT);
        when(initializer.hasCachedRoot(ROOT)).thenReturn(true);
        for (int i = 0; i < props.getSyncFailureAlertThreshold(); i++) {
            storage.incrementSyncFailure(ROOT, 60_000);
        }

        m.handleSubscribe(ROOT, "u1", "s1");

        verify(initializer).initializeHierarchy(ROOT);
    }

    @Test
    void secondUserNeverReloads() {
        PresenceSessionManager m = manager();
        storage.insertTracker(ROOT, "s0", "u0", "0");

        m.handleSubscribe(ROOT, "u1", "s1");

        verify(initializer, never()).initializeHierarchy(ROOT);
    }

    // ===== 예약 표시 =====

    @Test
    void pendingSyncMarkerIsSetOnLeaveAndClearedOnRejoin() {
        props.setSyncDelay(5);
        PresenceSessionManager m = manager();
        m.handleSubscribe(ROOT, "u1", "s1");

        m.handleDisconnect("u1", "s1");
        assertThat(storage.hasPendingSync(ROOT)).isTrue();

        m.handleSubscribe(ROOT, "u1", "s2"); // 같은 파드 재입장 → 예약 취소
        assertThat(storage.hasPendingSync(ROOT)).isFalse();
    }

    @Test
    void pendingSyncMarkerIsClearedAfterSync() {
        props.setSyncDelay(0);
        PresenceSessionManager m = manager();
        m.handleSubscribe(ROOT, "u1", "s1");

        m.handleDisconnect("u1", "s1");

        verify(sync).syncToDatabase(ROOT);
        assertThat(storage.hasPendingSync(ROOT)).isFalse();
    }

    // ===== 실패 횟수 (U11) =====

    @Test
    void dataFailureOnLeaveIsCounted() {
        props.setSyncDelay(0);
        PresenceSessionManager m = manager();
        doThrow(new DataIntegrityViolationException("uk")).when(sync).syncToDatabase(ROOT);
        m.handleSubscribe(ROOT, "u1", "s1");

        m.handleDisconnect("u1", "s1");

        assertThat(storage.getSyncFailureCount(ROOT)).isEqualTo(1);
    }

    @Test
    void connectionFailureOnLeaveIsNotCounted() {
        props.setSyncDelay(0);
        PresenceSessionManager m = manager();
        doThrow(new CannotCreateTransactionException("db down")).when(sync).syncToDatabase(ROOT);
        m.handleSubscribe(ROOT, "u1", "s1");

        m.handleDisconnect("u1", "s1");

        assertThat(storage.getSyncFailureCount(ROOT)).isZero();
    }

    // ===== 종료 정리 (U5, U13) =====

    @Test
    void U5_shutdownFlushDisconnectsSessionsAndSyncsImmediately() {
        props.setSyncDelay(30); // 예약만 해 두면 30초 뒤에야 돈다
        PresenceSessionManager m = manager();
        m.handleSubscribe(ROOT, "u1", "s1");

        m.flushOnShutdown(2_000);

        verify(sync, timeout(1_000)).syncToDatabase(ROOT);
        assertThat(storage.hasTracker(ROOT)).isFalse();
        assertThat(storage.hasPendingSync(ROOT)).isFalse();
        assertThat(m.isHandledAtShutdown("s1")).isTrue();
    }

    @Test
    void U13_lateDisconnectAfterShutdownFlushIsIgnored() {
        props.setSyncDelay(0);
        PresenceSessionManager m = manager();
        m.handleSubscribe(ROOT, "u1", "s1");
        m.flushOnShutdown(2_000);
        verify(sync, times(1)).syncToDatabase(ROOT);

        // 같은 세션 ID 로 다시 매핑을 만들어 둬도, 늦은 연결 종료 콜백은 처리하지 않는다
        storage.insertTracker(ROOT, "s1", "u1", "0");
        storage.mapSessionToRoot("s1", ROOT, 60);
        m.handleDisconnect("u1", "s1");

        assertThat(storage.hasTracker(ROOT)).isTrue();
        verify(sync, after(100).times(1)).syncToDatabase(anyString());
    }

    @Test
    void shutdownFlushDoesNotSyncRoomStillUsedOnAnotherPod() {
        props.setSyncDelay(30);
        PresenceSessionManager m = manager();
        m.handleSubscribe(ROOT, "u1", "s1");
        storage.insertTracker(ROOT, "other-pod-session", "u2", "0");

        m.flushOnShutdown(2_000);

        verify(sync, after(100).never()).syncToDatabase(anyString());
        assertThat(storage.hasTracker(ROOT)).isTrue();
    }

    @Test
    void shutdownFlushRetriesWhileLockIsHeld() throws Exception {
        props.setSyncDelay(30);
        PresenceSessionManager m = manager();
        m.handleSubscribe(ROOT, "u1", "s1");
        String held = storage.tryAcquireSyncLock(ROOT, 30_000).orElseThrow();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            storage.releaseSyncLock(ROOT, held);
        });
        releaser.start();

        m.flushOnShutdown(3_000);
        releaser.join();

        verify(sync).syncToDatabase(ROOT);
    }
}
