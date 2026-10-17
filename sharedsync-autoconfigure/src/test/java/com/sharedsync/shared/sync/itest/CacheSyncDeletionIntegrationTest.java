package com.sharedsync.shared.sync.itest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sharedsync.shared.listener.CacheInitializer;
import com.sharedsync.shared.repository.InMemoryCacheStore;
import com.sharedsync.shared.storage.InMemoryPresenceStorage;
import com.sharedsync.shared.sync.CacheSyncService;
import com.sharedsync.shared.sync.itest.SyncFixtures.Board;
import com.sharedsync.shared.sync.itest.SyncFixtures.BoardRepository;
import com.sharedsync.shared.sync.itest.SyncFixtures.Card;
import com.sharedsync.shared.sync.itest.SyncFixtures.CardDto;
import com.sharedsync.shared.sync.itest.SyncFixtures.CardRepository;
import com.sharedsync.shared.sync.itest.SyncFixtures.Lane;
import com.sharedsync.shared.sync.itest.SyncFixtures.LaneDto;
import com.sharedsync.shared.sync.itest.SyncFixtures.LaneRepository;
import com.sharedsync.shared.sync.itest.SyncFixtures.Note;
import com.sharedsync.shared.sync.itest.SyncFixtures.NoteRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 캐시 → DB 동기화의 삭제·트랜잭션 경계(묶음 A: 버그 2, 3, 11)를 실제 PostgreSQL 에서 검증한다.
 *
 * <p>전제: localhost:5432/sharedsync_test (postgres/postgres). 없으면 자동 스킵. 다른 DB를 쓰려면
 * SHAREDSYNC_TEST_DB_URL / _USER / _PASSWORD 환경변수로 바꾼다. 동기화는 실제로 커밋돼야
 * afterCommit 정리를 볼 수 있으므로 테스트 메서드에 트랜잭션을 걸지 않는다.</p>
 */
@EnabledIf("dbAvailable")
@SpringBootTest(classes = CacheSyncDeletionIntegrationTest.TestConfig.class, properties = {
        "spring.datasource.url=${SHAREDSYNC_TEST_DB_URL:jdbc:postgresql://localhost:5432/sharedsync_test}",
        "spring.datasource.username=${SHAREDSYNC_TEST_DB_USER:postgres}",
        "spring.datasource.password=${SHAREDSYNC_TEST_DB_PASSWORD:postgres}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.open-in-view=false"
})
class CacheSyncDeletionIntegrationTest {

    static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    static boolean dbAvailable() {
        try (Connection c = DriverManager.getConnection(
                env("SHAREDSYNC_TEST_DB_URL", "jdbc:postgresql://localhost:5432/sharedsync_test"),
                env("SHAREDSYNC_TEST_DB_USER", "postgres"), env("SHAREDSYNC_TEST_DB_PASSWORD", "postgres"))) {
            return c != null;
        } catch (Exception e) {
            return false;
        }
    }

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
    NoteRepository notes;
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

    // ===== 픽스처 헬퍼 =====

    /** DB 에 보드 1개, 레인 2개(a, b), 레인 a 에 카드 2개를 만들고 캐시에 적재한다. */
    private Fixture board(String name) {
        Fixture f = new Fixture();
        f.board = UUID.randomUUID();
        f.laneA = UUID.randomUUID();
        f.laneB = UUID.randomUUID();
        f.card1 = UUID.randomUUID();
        f.card2 = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            Board b = new Board(f.board, name);
            em.persist(b);
            Lane a = new Lane(f.laneA, b, "a");
            Lane bb = new Lane(f.laneB, b, "b");
            em.persist(a);
            em.persist(bb);
            em.persist(new Card(f.card1, a, "c1"));
            em.persist(new Card(f.card2, a, "c2"));
        });
        cacheInitializer.initializeHierarchy(f.board.toString());
        return f;
    }

    static class Fixture {
        UUID board, laneA, laneB, card1, card2;
    }

    private boolean exists(Class<?> type, UUID id) {
        return Boolean.TRUE.equals(tx.execute(s -> em.find(type, id) != null));
    }

    private String cardTitle(UUID id) {
        String[] out = new String[1];
        tx.executeWithoutResult(s -> out[0] = em.find(Card.class, id).getTitle());
        return out[0];
    }

    private UUID cardLane(UUID id) {
        UUID[] out = new UUID[1];
        tx.executeWithoutResult(s -> out[0] = em.find(Card.class, id).getLane().getLaneId());
        return out[0];
    }

    private boolean noteHasLane(UUID id) {
        boolean[] out = new boolean[1];
        tx.executeWithoutResult(s -> out[0] = em.find(Note.class, id).getLane() != null);
        return out[0];
    }

    /** 같은 key 의 레인을 하나 더 캐시에 넣어, 이 보드의 동기화가 유니크 제약으로 매번 실패하게 만든다. */
    private UUID breakBoard(Fixture f) {
        UUID dup = UUID.randomUUID();
        lanes.save(LaneDto.of(dup, f.board, "a"));
        return dup;
    }

    private void periodicSync(UUID board) {
        syncService.syncToDatabase(board.toString(), true);
    }

    // ===== T1, T2: 루트별 삭제 추적 (버그 2) =====

    @Test
    void T1_failingBoardDoesNotSwallowOtherBoardsDeletion() {
        Fixture a = board("A");
        Fixture b = board("B");
        breakBoard(a);
        cards.deleteById(b.card1);

        assertThatThrownBy(() -> periodicSync(a.board)).isInstanceOf(Exception.class);

        // A 의 실패가 B 의 삭제를 가져가지 않았다
        assertThat(exists(Card.class, b.card1)).isTrue();
        assertThat(cards.findDtoById(b.card1)).isNull();

        periodicSync(b.board);
        assertThat(exists(Card.class, b.card1)).isFalse();
        assertThat(exists(Card.class, b.card2)).isTrue();
    }

    @Test
    void T2_deletionIsRetriedAfterFailedSync() {
        Fixture a = board("A");
        UUID dup = breakBoard(a);
        cards.deleteById(a.card1);

        assertThatThrownBy(() -> periodicSync(a.board)).isInstanceOf(Exception.class);
        assertThat(exists(Card.class, a.card1)).isTrue();

        // 실패 원인 제거 후 다음 동기화가 같은 삭제를 다시 시도한다
        lanes.deleteCacheOnlyById(dup);
        periodicSync(a.board);
        assertThat(exists(Card.class, a.card1)).isFalse();
    }

    // ===== T3~T6: 자식이 있는 부모 삭제 (버그 3) =====

    @Test
    void T3_deletingLaneWithCardsSucceeds() {
        Fixture f = board("F");
        lanes.deleteById(f.laneA);

        periodicSync(f.board);

        assertThat(exists(Lane.class, f.laneA)).isFalse();
        assertThat(exists(Card.class, f.card1)).isFalse();
        assertThat(exists(Card.class, f.card2)).isFalse();
        assertThat(exists(Lane.class, f.laneB)).isTrue();
    }

    @Test
    void T4_untrackedDbChildIsBulkDeletedWithParent() {
        Fixture f = board("F");
        UUID stray = UUID.randomUUID();
        tx.executeWithoutResult(s -> em.persist(new Card(stray, em.find(Lane.class, f.laneA), "db-only")));

        lanes.deleteById(f.laneA);
        periodicSync(f.board);

        assertThat(exists(Lane.class, f.laneA)).isFalse();
        assertThat(exists(Card.class, stray)).isFalse();
    }

    @Test
    void T5_cardMovedToAnotherLaneSurvivesOriginalLaneDeletion() {
        Fixture f = board("F");
        cards.update(CardDto.of(f.card1, f.laneB, null));
        lanes.deleteById(f.laneA);

        periodicSync(f.board);

        assertThat(exists(Lane.class, f.laneA)).isFalse();
        assertThat(exists(Card.class, f.card1)).isTrue();
        assertThat(cardLane(f.card1)).isEqualTo(f.laneB);
        assertThat(exists(Card.class, f.card2)).isFalse();
    }

    @Test
    void T6_nullableFkChildIsLeftToDatabase() {
        Fixture f = board("F");
        UUID note = UUID.randomUUID();
        // 캐시 적재 후 DB 에만 생긴 행 — 서버는 추적하지 않는다
        tx.executeWithoutResult(s -> em.persist(new Note(note, em.find(Lane.class, f.laneA), "n")));

        lanes.deleteById(f.laneA);
        periodicSync(f.board);

        assertThat(exists(Lane.class, f.laneA)).isFalse();
        assertThat(exists(Note.class, note)).isTrue();
        assertThat(noteHasLane(note)).isFalse();
    }

    // ===== T7, T8: 퇴장 동기화 중단 (버그 11) =====

    @Test
    void T7_userReturningMidSyncRollsBackEverything() {
        Fixture f = board("F");
        cards.update(CardDto.of(f.card2, null, "edited"));
        cards.deleteById(f.card1);

        // 1: 시작 확인, 2: 루트 재귀, 3: 레인 재귀 → 여기서 사용자가 돌아온다
        presence.trackerAtCall = n -> n >= 3;
        syncService.syncToDatabase(f.board.toString(), false); // 예전처럼 정상 반환

        assertThat(exists(Card.class, f.card1)).isTrue();
        assertThat(cardTitle(f.card2)).isEqualTo("c2");
        // 캐시와 추적이 그대로 남아 다음 동기화가 이어받는다
        assertThat(boards.findDtoById(f.board)).isNotNull();
        assertThat(cards.findDtoById(f.card2).title).isEqualTo("edited");

        presence.trackerAtCall = null;
        periodicSync(f.board);
        assertThat(exists(Card.class, f.card1)).isFalse();
        assertThat(cardTitle(f.card2)).isEqualTo("edited");
    }

    @Test
    void T8_userReturningAfterCommitKeepsCache() {
        Fixture probe = board("P");
        presence.calls.set(0);
        syncService.syncToDatabase(probe.board.toString(), false);
        int callsPerSync = presence.calls.get(); // 마지막 호출이 커밋 후 캐시 삭제 직전 확인

        Fixture f = board("F");
        cards.update(CardDto.of(f.card2, null, "edited"));
        presence.calls.set(0);
        presence.trackerAtCall = n -> n >= callsPerSync;
        syncService.syncToDatabase(f.board.toString(), false);

        assertThat(cardTitle(f.card2)).isEqualTo("edited");
        assertThat(boards.findDtoById(f.board)).isNotNull();
        assertThat(cards.findDtoById(f.card2)).isNotNull();
    }

    // ===== T10, T11: 예전 Set 호환, undo 복원 =====

    @Test
    void T10_legacySetEntriesAreProcessedOnlyByTheirOwnBoard() {
        Fixture a = board("A");
        Fixture b = board("B");
        UUID missing = UUID.randomUUID();
        cards.deleteCacheOnlyById(a.card1);
        cards.deleteCacheOnlyById(b.card1);
        cacheStore.addToSet("synccard:DELETED", a.card1.toString());
        cacheStore.addToSet("synccard:DELETED", b.card1.toString());
        cacheStore.addToSet("synccard:DELETED", missing.toString());

        periodicSync(a.board);

        assertThat(exists(Card.class, a.card1)).isFalse();
        assertThat(exists(Card.class, b.card1)).isTrue();
        assertThat(cacheStore.getSet("synccard:DELETED")).containsExactly(b.card1.toString());
    }

    @Test
    void T11_restoredEntityIsNotDeleted() {
        Fixture f = board("F");
        CardDto before = cards.findDtoById(f.card1);
        cards.deleteById(f.card1);
        cards.save(before); // undo 복원과 같은 경로

        periodicSync(f.board);
        assertThat(exists(Card.class, f.card1)).isTrue();
    }

    // ===== 설정 =====

    /**
     * Redis 의 DEL 처럼 키 종류(값·Set·해시)와 상관없이 지운다. InMemoryCacheStore.delete 는 값만 지워서,
     * 예전 코드가 삭제 Set 을 비우는 동작(= 버그 2의 원인)이 메모리 저장소에서는 재현되지 않는다.
     */
    static class RedisLikeInMemoryCacheStore extends InMemoryCacheStore<Object> {
        @Override
        public void delete(String key) {
            super.delete(key);
            for (String member : new java.util.ArrayList<>(getSet(key))) {
                removeFromSet(key, member);
            }
            for (String field : new java.util.ArrayList<>(hashkeys(key))) {
                hashDelete(key, field);
            }
        }
    }

    /** hasTracker 호출 순번으로 "사용자가 돌아온 순간"을 흉내 낸다. */
    static class HookedPresenceStorage extends InMemoryPresenceStorage {
        final AtomicInteger calls = new AtomicInteger();
        volatile IntPredicate trackerAtCall;

        @Override
        public boolean hasTracker(String rootId) {
            int n = calls.incrementAndGet();
            IntPredicate hook = trackerAtCall;
            if (hook != null && hook.test(n)) {
                return true;
            }
            return super.hasTracker(rootId);
        }
    }

    @Configuration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            TransactionAutoConfiguration.class
    })
    @EntityScan(basePackageClasses = SyncFixtures.class)
    static class TestConfig {

        @Bean(name = "globalCacheStore")
        InMemoryCacheStore<Object> globalCacheStore() {
            return new RedisLikeInMemoryCacheStore();
        }

        @Bean
        HookedPresenceStorage presenceStorage() {
            return new HookedPresenceStorage();
        }

        @Bean
        BoardRepository boardRepository() {
            return new BoardRepository();
        }

        @Bean
        LaneRepository laneRepository() {
            return new LaneRepository();
        }

        @Bean
        CardRepository cardRepository() {
            return new CardRepository();
        }

        @Bean
        NoteRepository noteRepository() {
            return new NoteRepository();
        }

        @Bean
        CacheSyncService cacheSyncService(java.util.List<com.sharedsync.shared.repository.AutoCacheRepository<?, ?, ?>> repos,
                HookedPresenceStorage presenceStorage) {
            return new CacheSyncService(repos, presenceStorage);
        }

        @Bean
        CacheInitializer cacheInitializer(ApplicationContext context, HookedPresenceStorage presenceStorage) {
            return new CacheInitializer(context, presenceStorage);
        }
    }
}
