package com.sharedsync.shared.sync.deferral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sharedsync.shared.listener.CacheInitializer;
import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.repository.InMemoryCacheStore;
import com.sharedsync.shared.storage.InMemoryPresenceStorage;
import com.sharedsync.shared.storage.PresenceStorage;
import com.sharedsync.shared.sync.CacheSyncService;
import com.sharedsync.shared.sync.UniqueConstraintDeferral;
import com.sharedsync.shared.sync.deferral.DeferralFixtures.Item;
import com.sharedsync.shared.sync.deferral.DeferralFixtures.ItemDto;
import com.sharedsync.shared.sync.deferral.DeferralFixtures.ItemRepository;
import com.sharedsync.shared.sync.deferral.DeferralFixtures.Shelf;
import com.sharedsync.shared.sync.deferral.DeferralFixtures.ShelfRepository;
import com.sharedsync.shared.sync.deferral.DeferralFixtures.Slot;
import com.sharedsync.shared.sync.deferral.DeferralFixtures.SlotDto;
import com.sharedsync.shared.sync.deferral.DeferralFixtures.SlotRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 묶음 E: 캐시의 최종 상태가 유니크 제약에 맞으면, 동기화가 행을 쓰는 순서와 상관없이 DB에 들어가야 한다.
 *
 * <p>전제는 {@code CacheSyncDeletionIntegrationTest} 와 같다(로컬 PostgreSQL, 없으면 스킵, 환경변수로 변경).
 * {@code syncService} 는 유니크 제약을 미루는 서비스, {@code legacySyncService} 는 예전 생성자로 만든 서비스다.
 * 같은 편집이 legacy 에서는 실패해야 이 테스트가 버그를 잡는다는 뜻이 된다.</p>
 */
@EnabledIf("dbAvailable")
@SpringBootTest(classes = UniqueConstraintDeferralIntegrationTest.TestConfig.class, properties = {
        "spring.datasource.url=${SHAREDSYNC_TEST_DB_URL:jdbc:postgresql://localhost:5432/sharedsync_test}",
        "spring.datasource.username=${SHAREDSYNC_TEST_DB_USER:postgres}",
        "spring.datasource.password=${SHAREDSYNC_TEST_DB_PASSWORD:postgres}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.open-in-view=false"
})
class UniqueConstraintDeferralIntegrationTest {

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
    @Qualifier("legacySyncService")
    CacheSyncService legacySyncService;
    @Autowired
    CacheInitializer cacheInitializer;
    @Autowired
    InMemoryCacheStore<Object> cacheStore;
    @Autowired
    ShelfRepository shelves;
    @Autowired
    SlotRepository slots;
    @Autowired
    ItemRepository items;
    @Autowired
    PlatformTransactionManager txManager;
    @PersistenceContext
    EntityManager em;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("delete from def_item").executeUpdate();
            em.createNativeQuery("delete from def_slot").executeUpdate();
            em.createNativeQuery("delete from def_shelf").executeUpdate();
        });
        setSlotUniqueDeferrable(true);
        cacheStore.clear();
    }

    // ===== 픽스처 헬퍼 =====

    /** 앱의 마이그레이션과 같은 일: 위치 유니크 제약을 지우고 같은 이름·컬럼으로 다시 만든다. */
    private void setSlotUniqueDeferrable(boolean deferrable) {
        tx.executeWithoutResult(s -> em.createNativeQuery("alter table def_slot drop constraint if exists "
                + DeferralFixtures.SLOT_UNIQUE + ", add constraint " + DeferralFixtures.SLOT_UNIQUE
                + " unique (shelf_id, slot_position)" + (deferrable ? " deferrable initially immediate" : ""))
                .executeUpdate());
    }

    /**
     * 동기화가 칸을 순회하는 순서(부모 인덱스의 ID 를 {@code HashSet<String>} 에 모은 순서)대로 n개의 ID 를
     * 돌려준다. 이 순서로 위치 1, 2, … 를 주면 "앞 위치의 칸이 먼저 UPDATE 된다".
     */
    private static List<UUID> idsInSyncOrder(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(UUID.randomUUID());
        }
        List<UUID> ordered = new ArrayList<>();
        for (String s : new HashSet<>(ids.stream().map(UUID::toString).toList())) {
            ordered.add(UUID.fromString(s));
        }
        return ordered;
    }

    /** 선반 하나와 칸(위치 1..n)을 DB 에 만들고 캐시에 적재한다. 칸 ID 는 위치 1 이 먼저 순회되는 순서다. */
    private Fixture shelf(int slotCount) {
        Fixture f = new Fixture();
        f.shelf = UUID.randomUUID();
        f.slots = idsInSyncOrder(slotCount);
        tx.executeWithoutResult(s -> {
            Shelf shelf = new Shelf(f.shelf, "S");
            em.persist(shelf);
            for (int i = 0; i < slotCount; i++) {
                em.persist(new Slot(f.slots.get(i), shelf, i + 1, "slot" + (i + 1)));
            }
        });
        cacheInitializer.initializeHierarchy(f.shelf.toString());
        return f;
    }

    private static final class Fixture {
        UUID shelf;
        List<UUID> slots;

        UUID slot(int position) {
            return slots.get(position - 1);
        }
    }

    private void move(Fixture f, int fromPosition, int toPosition) {
        slots.update(SlotDto.of(f.slot(fromPosition), f.shelf, toPosition, null));
    }

    private Integer dbPosition(UUID slot) {
        return tx.execute(s -> {
            Slot e = em.find(Slot.class, slot);
            return e == null ? null : e.getPosition();
        });
    }

    private String dbLabel(UUID slot) {
        return tx.execute(s -> em.find(Slot.class, slot).getLabel());
    }

    private UUID dbSlotOfItem(UUID item) {
        return tx.execute(s -> em.find(Item.class, item).getSlot().getSlotId());
    }

    private boolean exists(Class<?> type, UUID id) {
        return Boolean.TRUE.equals(tx.execute(s -> em.find(type, id) != null));
    }

    private void periodicSync(CacheSyncService service, Fixture f) {
        service.syncToDatabase(f.shelf.toString(), true);
    }

    // ===== E1: 기존 행을 옮기고 비운 자리에 새 행 (버그 13) =====

    @Test
    void E1_moveAndInsertIntoVacatedPosition() {
        Fixture f = shelf(2);
        UUID added = UUID.randomUUID();
        move(f, 2, 3);
        slots.save(SlotDto.of(added, f.shelf, 2, "new"));

        periodicSync(syncService, f);

        assertThat(dbPosition(f.slot(2))).isEqualTo(3);
        assertThat(dbPosition(added)).isEqualTo(2);
    }

    @Test
    void E1_legacyServiceFailsOnTheSameEdit() {
        Fixture f = shelf(2);
        move(f, 2, 3);
        slots.save(SlotDto.of(UUID.randomUUID(), f.shelf, 2, "new"));

        // Hibernate 는 한 flush 안에서 INSERT 를 UPDATE 보다 먼저 보낸다 — 지연이 없으면 항상 걸린다.
        assertThatThrownBy(() -> periodicSync(legacySyncService, f)).isInstanceOf(Exception.class);
        assertThat(dbPosition(f.slot(2))).isEqualTo(2);
    }

    // ===== E2: 여러 행을 한 칸씩 밀기 (버그 14·15) =====

    @Test
    void E2_shiftAllForward() {
        Fixture f = shelf(3);
        // 위치 1 이 먼저 UPDATE 되는 순서라, 지연이 없으면 1→2 가 아직 2에 있는 칸에 걸린다.
        move(f, 3, 4);
        move(f, 2, 3);
        move(f, 1, 2);

        periodicSync(syncService, f);

        assertThat(dbPosition(f.slot(1))).isEqualTo(2);
        assertThat(dbPosition(f.slot(2))).isEqualTo(3);
        assertThat(dbPosition(f.slot(3))).isEqualTo(4);
    }

    @Test
    void E2_legacyServiceFailsOnForwardShift() {
        Fixture f = shelf(3);
        move(f, 3, 4);
        move(f, 2, 3);
        move(f, 1, 2);

        assertThatThrownBy(() -> periodicSync(legacySyncService, f)).isInstanceOf(Exception.class);
        assertThat(dbPosition(f.slot(1))).isEqualTo(1);
    }

    @Test
    void E2_shiftAllBackward() {
        Fixture f = shelf(3);
        move(f, 1, 0);
        move(f, 2, 1);
        move(f, 3, 2);

        periodicSync(syncService, f);

        assertThat(dbPosition(f.slot(1))).isEqualTo(0);
        assertThat(dbPosition(f.slot(2))).isEqualTo(1);
        assertThat(dbPosition(f.slot(3))).isEqualTo(2);
    }

    // ===== E3: 맞바꾸기와 순환 (버그 16) — 어떤 순서로도 지연 없이는 안 된다 =====

    @Test
    void E3_swapTwo() {
        Fixture f = shelf(2);
        move(f, 1, 2);
        slots.update(SlotDto.of(f.slot(2), f.shelf, 1, null));

        periodicSync(syncService, f);

        assertThat(dbPosition(f.slot(1))).isEqualTo(2);
        assertThat(dbPosition(f.slot(2))).isEqualTo(1);
    }

    @Test
    void E3_legacyServiceFailsOnSwap() {
        Fixture f = shelf(2);
        move(f, 1, 2);
        slots.update(SlotDto.of(f.slot(2), f.shelf, 1, null));

        assertThatThrownBy(() -> periodicSync(legacySyncService, f)).isInstanceOf(Exception.class);
    }

    @Test
    void E3_rotateThree() {
        Fixture f = shelf(3);
        slots.update(SlotDto.of(f.slot(1), f.shelf, 2, null));
        slots.update(SlotDto.of(f.slot(2), f.shelf, 3, null));
        slots.update(SlotDto.of(f.slot(3), f.shelf, 1, null));

        periodicSync(syncService, f);

        assertThat(dbPosition(f.slot(1))).isEqualTo(2);
        assertThat(dbPosition(f.slot(2))).isEqualTo(3);
        assertThat(dbPosition(f.slot(3))).isEqualTo(1);
    }

    // ===== E4: 최종 상태가 규칙을 어기면 커밋에서 실패하고 아무것도 들어가지 않는다 =====

    @Test
    void E4_finalStateViolationFailsAtCommitAndRollsBack() {
        Fixture f = shelf(2);
        UUID added = UUID.randomUUID();
        move(f, 2, 1); // 위치 1 에 두 칸
        slots.save(SlotDto.of(added, f.shelf, 5, "new"));

        assertThatThrownBy(() -> periodicSync(syncService, f)).isInstanceOf(Exception.class);

        assertThat(dbPosition(f.slot(2))).isEqualTo(2);
        assertThat(exists(Slot.class, added)).isFalse();
        // 캐시는 그대로 남아 다음 동기화가 다시 시도한다
        assertThat(slots.findDtoById(f.slot(2)).position).isEqualTo(1);
        assertThat(slots.findDtoById(added)).isNotNull();
    }

    // ===== E5: 자식을 옮긴 부모 삭제 + 같은 키 부모 추가 (05 10-4, 묶음 B 의 알려진 한계) =====

    @Test
    void E5_parentWithMovedChildDeletedAndSameKeyParentAdded() {
        Fixture f = shelf(2);
        UUID item = UUID.randomUUID();
        tx.executeWithoutResult(s -> em.persist(new Item(item, em.getReference(Slot.class, f.slot(1)), "i")));
        cacheInitializer.initializeHierarchy(f.shelf.toString());
        UUID added = UUID.randomUUID();

        items.update(ItemDto.of(item, f.slot(2), null));
        slots.deleteById(f.slot(1));
        slots.save(SlotDto.of(added, f.shelf, 1, "new"));

        periodicSync(syncService, f);

        assertThat(exists(Slot.class, f.slot(1))).isFalse();
        assertThat(dbPosition(added)).isEqualTo(1);
        assertThat(dbSlotOfItem(item)).isEqualTo(f.slot(2));
    }

    // ===== E7: 동기화 밖에서는 지금처럼 문장마다 검사한다 (INITIALLY IMMEDIATE) =====

    @Test
    void E7_outsideSyncDuplicateFailsAtFlushNotCommit() {
        Fixture f = shelf(1);
        AtomicBoolean failedAtFlush = new AtomicBoolean();
        tx.executeWithoutResult(s -> {
            em.persist(new Slot(UUID.randomUUID(), em.getReference(Shelf.class, f.shelf), 1, "dup"));
            try {
                em.flush();
            } catch (Exception e) {
                failedAtFlush.set(true);
            }
            s.setRollbackOnly();
        });
        assertThat(failedAtFlush).isTrue();
    }

    // ===== E8: 떠 있는 동안 제약을 지연 불가로 되돌려도 동기화가 깨지지 않는다 =====

    @Test
    void E8_constraintTurnedNotDeferrableAtRuntime() {
        Fixture f = shelf(2);
        periodicSync(syncService, f); // 지연 가능한 상태에서 한 번 돈다
        setSlotUniqueDeferrable(false);
        try {
            slots.update(SlotDto.of(f.slot(1), f.shelf, null, "renamed"));
            periodicSync(syncService, f); // 목록이 기동 때 고정이었다면 SET CONSTRAINTS 에러로 여기서 실패한다
            assertThat(dbLabel(f.slot(1))).isEqualTo("renamed");

            // 지연이 없으니 예전처럼 걸린다
            move(f, 2, 3);
            slots.save(SlotDto.of(UUID.randomUUID(), f.shelf, 2, "new"));
            assertThatThrownBy(() -> periodicSync(syncService, f)).isInstanceOf(Exception.class);
        } finally {
            setSlotUniqueDeferrable(true);
        }
    }

    // ===== E10: 같은 이름의 지연 불가 제약이 다른 테이블에 있어도 동기화가 깨지지 않는다 =====

    @Test
    void E10_sameNamedNotDeferrableConstraintElsewhere() {
        tx.executeWithoutResult(s -> em.createNativeQuery("create table if not exists def_other (x int constraint "
                + DeferralFixtures.SLOT_UNIQUE + " check (x > 0))").executeUpdate());
        try {
            Fixture f = shelf(2);
            slots.update(SlotDto.of(f.slot(1), f.shelf, null, "renamed"));
            periodicSync(syncService, f);
            assertThat(dbLabel(f.slot(1))).isEqualTo("renamed");
        } finally {
            tx.executeWithoutResult(s -> em.createNativeQuery("drop table if exists def_other").executeUpdate());
        }
    }

    // ===== 설정 =====

    @Configuration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            TransactionAutoConfiguration.class
    })
    @EntityScan(basePackageClasses = DeferralFixtures.class)
    static class TestConfig {

        @Bean(name = "globalCacheStore")
        InMemoryCacheStore<Object> globalCacheStore() {
            return new InMemoryCacheStore<>();
        }

        @Bean
        PresenceStorage presenceStorage() {
            return new InMemoryPresenceStorage();
        }

        @Bean
        ShelfRepository shelfRepository() {
            return new ShelfRepository();
        }

        @Bean
        SlotRepository slotRepository() {
            return new SlotRepository();
        }

        @Bean
        ItemRepository itemRepository() {
            return new ItemRepository();
        }

        @Bean
        UniqueConstraintDeferral uniqueConstraintDeferral(List<AutoCacheRepository<?, ?, ?>> repos) {
            return new UniqueConstraintDeferral(repos, true);
        }

        @Bean
        @Primary
        CacheSyncService cacheSyncService(List<AutoCacheRepository<?, ?, ?>> repos, PresenceStorage presenceStorage,
                UniqueConstraintDeferral deferral) {
            return new CacheSyncService(repos, presenceStorage, deferral);
        }

        /** E9: 예전 생성자 — 유니크 제약을 미루지 않는다. */
        @Bean
        CacheSyncService legacySyncService(List<AutoCacheRepository<?, ?, ?>> repos, PresenceStorage presenceStorage) {
            return new CacheSyncService(repos, presenceStorage);
        }

        @Bean
        CacheInitializer cacheInitializer(ApplicationContext context, PresenceStorage presenceStorage) {
            return new CacheInitializer(context, presenceStorage);
        }
    }
}
