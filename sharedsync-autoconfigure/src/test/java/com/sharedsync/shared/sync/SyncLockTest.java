package com.sharedsync.shared.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.sharedsync.shared.history.HistoryService;
import com.sharedsync.shared.id.IdPoolService;
import com.sharedsync.shared.listener.CacheInitializer;
import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.presence.core.PresenceBroadcaster;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.presence.core.UserProvider;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.storage.InMemoryPresenceStorage;
import com.sharedsync.shared.transport.WebSocketSessionRegistry;

/** 동기화 잠금(묶음 A: 버그 4)과 퇴장 동기화 재시도. */
class SyncLockTest {

    private static final String ROOT = "room-1";

    // ===== 토큰 잠금 =====

    @Test
    void releaseWithAnotherTokenDoesNotUnlock() {
        InMemoryPresenceStorage storage = new InMemoryPresenceStorage();
        Optional<String> token = storage.tryAcquireSyncLock(ROOT, 30_000);
        assertThat(token).isPresent();
        assertThat(storage.tryAcquireSyncLock(ROOT, 30_000)).isEmpty();

        storage.releaseSyncLock(ROOT, "someone-else");
        assertThat(storage.isSyncing(ROOT)).isTrue();

        storage.releaseSyncLock(ROOT, token.get());
        assertThat(storage.isSyncing(ROOT)).isFalse();
    }

    @Test
    void tokenLockExcludesLegacyLock() {
        InMemoryPresenceStorage storage = new InMemoryPresenceStorage();
        assertThat(storage.tryAcquireSyncLock(ROOT, 30_000)).isPresent();
        assertThat(storage.acquireSyncLock(ROOT)).isFalse();
    }

    // ===== 주기 동기화 =====

    @Test
    void periodicSyncSkipsRoomWhileAnotherSyncHoldsTheLock() throws Exception {
        InMemoryPresenceStorage storage = new InMemoryPresenceStorage();
        storage.insertTracker(ROOT, "s1", "u1", "0");
        CacheSyncService sync = mock(CacheSyncService.class);
        IdPoolService idPool = mock(IdPoolService.class);
        when(idPool.isRedisPoolIntact()).thenReturn(true);
        PeriodicSyncScheduler scheduler = new PeriodicSyncScheduler(sync, storage, new SharedSyncPresenceProperties(),
                idPool);

        String held = storage.tryAcquireSyncLock(ROOT, 30_000).orElseThrow();
        runOnce(scheduler);
        verify(sync, never()).syncToDatabase(anyString(), anyBoolean());

        storage.releaseSyncLock(ROOT, held);
        runOnce(scheduler);
        verify(sync, times(1)).syncToDatabase(ROOT, true);
        // 끝나면 자기 잠금을 푼다
        assertThat(storage.isSyncing(ROOT)).isFalse();
    }

    private static void runOnce(PeriodicSyncScheduler scheduler) throws Exception {
        Method m = PeriodicSyncScheduler.class.getDeclaredMethod("syncAllActiveRooms");
        m.setAccessible(true);
        m.invoke(scheduler);
    }

    // ===== 퇴장 동기화 재시도 =====

    @Test
    void disconnectSyncRetriesUntilLockIsFree() throws Exception {
        InMemoryPresenceStorage storage = new InMemoryPresenceStorage();
        CacheSyncService sync = mock(CacheSyncService.class);
        PresenceSessionManager manager = manager(storage, sync);
        storage.insertTracker(ROOT, "s1", "u1", "0");
        storage.mapSessionToRoot("s1", ROOT, 60);

        String held = storage.tryAcquireSyncLock(ROOT, 30_000).orElseThrow();
        manager.handleDisconnect("u1", "s1");

        // 잠금이 잡혀 있는 동안은 실행되지 않는다(예전에는 여기서 그냥 끝났다)
        verify(sync, after(100).never()).syncToDatabase(anyString());

        storage.releaseSyncLock(ROOT, held);
        verify(sync, timeout(2_000)).syncToDatabase(ROOT);
        manager.cleanup();
    }

    @Test
    void disconnectSyncRetryStopsWhenUserReturns() throws Exception {
        InMemoryPresenceStorage storage = new InMemoryPresenceStorage();
        CacheSyncService sync = mock(CacheSyncService.class);
        PresenceSessionManager manager = manager(storage, sync);
        storage.insertTracker(ROOT, "s1", "u1", "0");
        storage.mapSessionToRoot("s1", ROOT, 60);

        String held = storage.tryAcquireSyncLock(ROOT, 30_000).orElseThrow();
        manager.handleDisconnect("u1", "s1");

        storage.insertTracker(ROOT, "s2", "u1", "0"); // 재시도 중 사용자가 돌아옴
        storage.releaseSyncLock(ROOT, held);

        verify(sync, after(500).never()).syncToDatabase(anyString());
        manager.cleanup();
    }

    @SuppressWarnings("unchecked")
    private static PresenceSessionManager manager(InMemoryPresenceStorage storage, CacheSyncService sync) {
        SharedSyncPresenceProperties props = new SharedSyncPresenceProperties();
        props.setSyncDelay(0);
        props.setSyncLockRetryIntervalMs(20);
        props.setSyncLockRetryAttempts(50);
        PresenceRootResolver resolver = mock(PresenceRootResolver.class);
        when(resolver.getChannel()).thenReturn("ch");
        return new PresenceSessionManager(storage, mock(PresenceBroadcaster.class), mock(UserProvider.class),
                mock(CacheInitializer.class), sync, mock(HistoryService.class), resolver,
                new SharedSyncAuthProperties(), props, new SharedSyncWebSocketProperties(),
                (ObjectProvider<WebSocketSessionRegistry>) mock(ObjectProvider.class));
    }
}
