package com.sharedsync.shared.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

import com.sharedsync.shared.id.IdPoolService;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.storage.InMemoryPresenceStorage;

/** 묶음 B: 주인 없는 캐시 저장(B3)과 연속 실패 횟수. */
class OrphanCacheSyncTest {

    private static final String ROOT = "room-1";

    private InMemoryPresenceStorage storage;
    private CacheSyncService sync;
    private SharedSyncPresenceProperties props;
    private PeriodicSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        storage = new InMemoryPresenceStorage();
        sync = mock(CacheSyncService.class);
        props = new SharedSyncPresenceProperties();
        props.setOrphanGraceSeconds(0);
        IdPoolService idPool = mock(IdPoolService.class);
        when(idPool.isRedisPoolIntact()).thenReturn(true);
        scheduler = new PeriodicSyncScheduler(sync, storage, props, idPool);
        when(sync.cachedRootIds()).thenReturn(List.of(ROOT));
        when(sync.rootExistsInDatabase(ROOT)).thenReturn(true);
        storage.markCacheReady(ROOT);
    }

    private void runOnce() throws Exception {
        Method m = PeriodicSyncScheduler.class.getDeclaredMethod("syncAllActiveRooms");
        m.setAccessible(true);
        m.invoke(scheduler);
    }

    @Test
    void U7_orphanCacheIsSyncedAndLockReleased() throws Exception {
        runOnce();

        verify(sync).syncToDatabase(ROOT, false);
        assertThat(storage.isSyncing(ROOT)).isFalse();
    }

    @Test
    void U6_pendingSyncMarkerKeepsOrphanSyncAway() throws Exception {
        storage.markPendingSync(ROOT, 60_000);
        runOnce();
        verify(sync, never()).syncToDatabase(anyString(), anyBoolean());
    }

    @Test
    void U6_heldLockKeepsOrphanSyncAway() throws Exception {
        storage.tryAcquireSyncLock(ROOT, 30_000);
        runOnce();
        verify(sync, never()).syncToDatabase(anyString(), anyBoolean());
    }

    @Test
    void U6_trackerMeansNotOrphan() throws Exception {
        storage.insertTracker(ROOT, "s1", "u1", "0");
        runOnce();
        // 접속자가 있으면 주기 동기화(keepCache=true)만 돈다
        verify(sync, never()).syncToDatabase(ROOT, false);
        verify(sync).syncToDatabase(ROOT, true);
    }

    @Test
    void U6_waitsForGracePeriod() throws Exception {
        props.setOrphanGraceSeconds(120);
        runOnce();
        runOnce();
        verify(sync, never()).syncToDatabase(anyString(), anyBoolean());
    }

    @Test
    void cacheWithoutReadyMarkIsLeftAlone() throws Exception {
        storage.clearCacheReady(ROOT);
        runOnce();
        verify(sync, never()).syncToDatabase(anyString(), anyBoolean());
    }

    @Test
    void disabledSettingTurnsItOff() throws Exception {
        props.setOrphanSyncEnabled(false);
        runOnce();
        verify(sync, never()).syncToDatabase(anyString(), anyBoolean());
    }

    @Test
    void U9_blockedRoomIsSkipped() throws Exception {
        for (int i = 0; i < props.getSyncFailureAlertThreshold(); i++) {
            storage.incrementSyncFailure(ROOT, 60_000);
        }
        runOnce();
        verify(sync, never()).syncToDatabase(anyString(), anyBoolean());
    }

    @Test
    void U10_missingRootIsEvictedWithoutSync() throws Exception {
        when(sync.rootExistsInDatabase(ROOT)).thenReturn(false);
        runOnce();
        verify(sync).evictRootWithoutSync(ROOT);
        verify(sync, never()).syncToDatabase(anyString(), anyBoolean());
    }

    @Test
    void U10_databaseLookupFailureDeletesNothing() throws Exception {
        when(sync.rootExistsInDatabase(ROOT)).thenThrow(new CannotCreateTransactionException("db down"));
        runOnce();
        verify(sync, never()).evictRootWithoutSync(anyString());
        verify(sync, never()).syncToDatabase(anyString(), anyBoolean());
        assertThat(storage.getSyncFailureCount(ROOT)).isZero();
        assertThat(storage.isSyncing(ROOT)).isFalse();
    }

    @Test
    void orphanSyncFailureIsCounted() throws Exception {
        doThrow(new DataIntegrityViolationException("uk")).when(sync).syncToDatabase(ROOT, false);
        runOnce();
        assertThat(storage.getSyncFailureCount(ROOT)).isEqualTo(1);
    }

    // ===== 주기 동기화 실패 횟수 (U11) =====

    @Test
    void U11_periodicConnectionFailureIsNotCounted() throws Exception {
        storage.insertTracker(ROOT, "s1", "u1", "0");
        doThrow(new CannotCreateTransactionException("db down")).when(sync).syncToDatabase(ROOT, true);
        runOnce();
        assertThat(storage.getSyncFailureCount(ROOT)).isZero();
    }

    @Test
    void periodicDataFailureIsCounted() throws Exception {
        storage.insertTracker(ROOT, "s1", "u1", "0");
        doThrow(new DataIntegrityViolationException("uk")).when(sync).syncToDatabase(ROOT, true);
        runOnce();
        runOnce();
        assertThat(storage.getSyncFailureCount(ROOT)).isEqualTo(2);
    }
}
