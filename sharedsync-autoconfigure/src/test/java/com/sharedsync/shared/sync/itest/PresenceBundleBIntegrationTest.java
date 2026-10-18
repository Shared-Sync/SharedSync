package com.sharedsync.shared.sync.itest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sharedsync.shared.listener.CacheInitializer;
import com.sharedsync.shared.repository.InMemoryCacheStore;
import com.sharedsync.shared.sync.CacheSyncService;
import com.sharedsync.shared.sync.itest.CacheSyncDeletionIntegrationTest.HookedPresenceStorage;
import com.sharedsync.shared.sync.itest.SyncFixtures.Board;
import com.sharedsync.shared.sync.itest.SyncFixtures.BoardRepository;
import com.sharedsync.shared.sync.itest.SyncFixtures.Card;
import com.sharedsync.shared.sync.itest.SyncFixtures.CardDto;
import com.sharedsync.shared.sync.itest.SyncFixtures.CardRepository;
import com.sharedsync.shared.sync.itest.SyncFixtures.Lane;
import com.sharedsync.shared.sync.itest.SyncFixtures.LaneDto;
import com.sharedsync.shared.sync.itest.SyncFixtures.LaneRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 묶음 B(버그 12, "온전히 채워진 상태" 표시, 루트가 사라진 캐시)를 실제 PostgreSQL 에서 검증한다.
 * 전제와 설정은 {@link CacheSyncDeletionIntegrationTest} 와 같다.
 */
@EnabledIf("com.sharedsync.shared.sync.itest.CacheSyncDeletionIntegrationTest#dbAvailable")
@SpringBootTest(classes = CacheSyncDeletionIntegrationTest.TestConfig.class, properties = {
        "spring.datasource.url=${SHAREDSYNC_TEST_DB_URL:jdbc:postgresql://localhost:5432/sharedsync_test}",
        "spring.datasource.username=${SHAREDSYNC_TEST_DB_USER:postgres}",
        "spring.datasource.password=${SHAREDSYNC_TEST_DB_PASSWORD:postgres}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.open-in-view=false"
})
class PresenceBundleBIntegrationTest {

    @Autowired
    CacheSyncService syncService;
    @Autowired
    CacheInitializer cacheInitializer;
    @Autowired
    HookedPresenceStorage presence;
    @Autowired
    InMemoryCacheStore<Object> cacheStore;
    @Autowired
    BoardRepository boards;
    @Autowired
    LaneRepository lanes;
    @Autowired
    CardRepository cards;
    @Autowired
    PlatformTransactionManager txManager;
    @PersistenceContext
    EntityManager em;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("delete from sync_note").executeUpdate();
            em.createNativeQuery("delete from sync_card").executeUpdate();
            em.createNativeQuery("delete from sync_lane").executeUpdate();
            em.createNativeQuery("delete from sync_board").executeUpdate();
        });
        cacheStore.clear();
        presence.trackerAtCall = null;
        presence.calls.set(0);
    }

    /** DB 에 보드 1개, 레인 2개(a, b), 레인 a 에 카드 2개를 만들고 캐시에 적재한다. */
    private UUID[] board(String name) {
        UUID board = UUID.randomUUID(), laneA = UUID.randomUUID(), laneB = UUID.randomUUID();
        UUID card1 = UUID.randomUUID(), card2 = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            Board b = new Board(board, name);
            em.persist(b);
            Lane a = new Lane(laneA, b, "a");
            em.persist(a);
            em.persist(new Lane(laneB, b, "b"));
            em.persist(new Card(card1, a, "c1"));
            em.persist(new Card(card2, a, "c2"));
        });
        cacheInitializer.initializeHierarchy(board.toString());
        return new UUID[] { board, laneA, laneB, card1, card2 };
    }

    private boolean exists(Class<?> type, UUID id) {
        return Boolean.TRUE.equals(tx.execute(s -> em.find(type, id) != null));
    }

    private UUID cardLane(UUID id) {
        return tx.execute(s -> em.find(Card.class, id).getLane().getLaneId());
    }

    // ===== 버그 12 =====

    @Test
    void T12_deleteLaneWithCardsAndAddSameKeyInOneWindow() {
        UUID[] f = board("F");
        UUID board = f[0], laneA = f[1], card1 = f[3], card2 = f[4];
        UUID newLane = UUID.randomUUID();
        lanes.deleteById(laneA);
        lanes.save(LaneDto.of(newLane, board, "a"));

        syncService.syncToDatabase(board.toString(), true);

        assertThat(exists(Lane.class, laneA)).isFalse();
        assertThat(exists(Card.class, card1)).isFalse();
        assertThat(exists(Card.class, card2)).isFalse();
        assertThat(exists(Lane.class, newLane)).isTrue();
    }

    @Test
    void T13_laneWhoseCardMovedAwayIsStillDeletedAfterSave() {
        UUID[] f = board("F");
        UUID board = f[0], laneA = f[1], laneB = f[2], card1 = f[3], card2 = f[4];
        cards.update(CardDto.of(card1, laneB, null));
        lanes.deleteById(laneA);

        syncService.syncToDatabase(board.toString(), true);

        // 옮긴 카드가 남아 있어 레인 a 는 저장 뒤에 지워진다(예전 순서). 옮긴 카드는 새 레인에 남는다.
        assertThat(exists(Lane.class, laneA)).isFalse();
        assertThat(cardLane(card1)).isEqualTo(laneB);
        assertThat(exists(Card.class, card2)).isFalse();
    }

    @Test
    void T13_knownLimit_movedCardPlusSameKeyLaneStillFailsAndRollsBack() {
        UUID[] f = board("F");
        UUID board = f[0], laneA = f[1], laneB = f[2], card1 = f[3], card2 = f[4];
        cards.update(CardDto.of(card1, laneB, null));
        lanes.deleteById(laneA);
        lanes.save(LaneDto.of(UUID.randomUUID(), board, "a"));

        // 설계 10-4: 레인 a 가 깨끗하지 않아 저장 뒤에 지워지므로, 같은 key 의 새 레인 INSERT 가 먼저 걸린다.
        assertThatThrownBy(() -> syncService.syncToDatabase(board.toString(), true)).isInstanceOf(Exception.class);

        // 전부 롤백: 먼저 지운 카드도 그대로다
        assertThat(exists(Lane.class, laneA)).isTrue();
        assertThat(cardLane(card1)).isEqualTo(laneA);
        assertThat(exists(Card.class, card2)).isTrue();
    }

    // ===== "온전히 채워진 상태" 표시 =====

    @Test
    void initialLoadMarksReady() {
        UUID[] f = board("F");
        assertThat(presence.isCacheReady(f[0].toString())).isTrue();
    }

    @Test
    void loadingMissingRootDoesNotMarkReady() {
        UUID missing = UUID.randomUUID();
        cacheInitializer.initializeHierarchy(missing.toString());
        assertThat(presence.isCacheReady(missing.toString())).isFalse();
    }

    @Test
    void U4_successfulLeaveSyncClearsReadyMarkWithCache() {
        UUID[] f = board("F");
        syncService.syncToDatabase(f[0].toString(), false);

        assertThat(presence.isCacheReady(f[0].toString())).isFalse();
        assertThat(boards.findDtoById(f[0])).isNull();
    }

    @Test
    void periodicSyncKeepsReadyMark() {
        UUID[] f = board("F");
        syncService.syncToDatabase(f[0].toString(), true);
        assertThat(presence.isCacheReady(f[0].toString())).isTrue();
    }

    @Test
    void U12_userReturningAfterCommitKeepsReadyMarkAndCache() {
        UUID[] probe = board("P");
        presence.calls.set(0);
        syncService.syncToDatabase(probe[0].toString(), false);
        int callsPerSync = presence.calls.get(); // 마지막 확인이 커밋 후 정리의 "트래커 없으면 표시 삭제"

        UUID[] f = board("F");
        presence.calls.set(0);
        presence.trackerAtCall = n -> n >= callsPerSync;
        syncService.syncToDatabase(f[0].toString(), false);

        assertThat(presence.isCacheReady(f[0].toString())).isTrue();
        assertThat(boards.findDtoById(f[0])).isNotNull();
    }

    @Test
    void successfulSyncClearsFailureCount() {
        UUID[] f = board("F");
        presence.incrementSyncFailure(f[0].toString(), 60_000);
        syncService.syncToDatabase(f[0].toString(), true);
        assertThat(presence.getSyncFailureCount(f[0].toString())).isZero();
    }

    // ===== 루트가 사라진 캐시 =====

    @Test
    void U10_rootMissingFromDatabaseIsEvictedWithoutSync() {
        UUID[] f = board("F");
        UUID board = f[0];
        cards.deleteById(f[3]); // 처리되지 않은 삭제 추적
        presence.incrementSyncFailure(board.toString(), 60_000);
        assertThat(syncService.rootExistsInDatabase(board.toString())).isTrue();

        tx.executeWithoutResult(s -> {
            em.createNativeQuery("delete from sync_card").executeUpdate();
            em.createNativeQuery("delete from sync_lane").executeUpdate();
            em.createNativeQuery("delete from sync_board").executeUpdate();
        });
        assertThat(syncService.rootExistsInDatabase(board.toString())).isFalse();

        syncService.evictRootWithoutSync(board.toString());

        assertThat(boards.findDtoById(board)).isNull();
        assertThat(lanes.findDtoById(f[1])).isNull();
        assertThat(cards.findDtoById(f[4])).isNull();
        assertThat(cacheStore.hashkeys("synccard:DELETED_BY_ROOT")).doesNotContain(f[3].toString());
        assertThat(presence.isCacheReady(board.toString())).isFalse();
        assertThat(presence.getSyncFailureCount(board.toString())).isZero();
        assertThat(exists(Board.class, board)).isFalse(); // 되살리지 않는다
    }

    @Test
    void cachedRootIdsListsOnlyRoots() {
        UUID[] a = board("A");
        UUID[] b = board("B");
        assertThat(syncService.cachedRootIds()).containsExactlyInAnyOrder(a[0].toString(), b[0].toString());
    }
}
