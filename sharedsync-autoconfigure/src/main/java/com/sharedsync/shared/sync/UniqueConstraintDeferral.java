package com.sharedsync.shared.sync;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.hibernate.Session;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.repository.support.ReflectionSupport;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 동기화 트랜잭션 안에서만 캐시 엔티티 테이블의 유니크 제약을 커밋 때 검사하게 한다.
 *
 * <p>동기화는 캐시의 최종 상태만 알고, 그 상태를 만드는 순서(어느 행을 먼저 쓰는지)는 캐시 순회 순서에 따라
 * 정해진다. 지연 불가 유니크 제약은 행 하나를 쓸 때마다 검사하므로, 최종 상태가 정상이어도 옮겨 적는 도중에
 * 같은 키가 두 줄이 되면 실패한다(행을 옮기고 빈 자리에 새 행, 여러 행을 한 칸씩 밀기, 맞바꾸기). 앱이 제약을
 * {@code DEFERRABLE INITIALLY IMMEDIATE} 로 선언해 두면, 여기서 그 제약만 {@code SET CONSTRAINTS ... DEFERRED}
 * 로 바꿔 커밋 때 최종 상태만 검사하게 한다. 최종 상태가 규칙을 어기면 커밋에서 실패하고 롤백된다.</p>
 *
 * <p>대상은 동기화할 때마다 같은 트랜잭션 안에서 카탈로그로 구한다. 지연 불가 제약의 이름을 넣으면
 * {@code SET CONSTRAINTS} 가 에러를 내고 트랜잭션이 깨지므로, 기동 때 구해 둔 목록이 실제 DB와 어긋나면
 * 모든 동기화가 실패하게 된다. 조회와 SET 은 세이브포인트 안에서 하고, 실패하면 세이브포인트로 되돌려
 * 예전처럼(지연 없이) 동기화를 계속한다.</p>
 *
 * <p>지연 가능한 제약이 없는 앱에서는 아무것도 하지 않는다. PostgreSQL 이 아니면 카탈로그 조회도 하지 않는다.
 * FK 는 미루지 않는다.</p>
 */
@Slf4j
public class UniqueConstraintDeferral implements ApplicationListener<ApplicationReadyEvent> {

    /**
     * 캐시 엔티티 테이블의 지연 가능 유니크·EXCLUDE 제약. 같은 스키마에 같은 이름의 제약이 하나라도 지연 불가면
     * 뺀다 — {@code SET CONSTRAINTS} 는 이름이 맞는 제약 모두에 적용되고, 그중 지연 불가가 있으면 에러가 난다.
     */
    private static final String FIND_DEFERRABLE = """
            select n.nspname, c.conname
              from pg_catalog.pg_constraint c
              join pg_catalog.pg_class r on r.oid = c.conrelid
              join pg_catalog.pg_namespace n on n.oid = c.connamespace
             where c.contype in ('u', 'x')
               and c.condeferrable
               and r.relname = any (?)
               and pg_catalog.pg_table_is_visible(r.oid)
               and not exists (select 1 from pg_catalog.pg_constraint o
                                where o.connamespace = c.connamespace
                                  and o.conname = c.conname
                                  and not o.condeferrable)
             order by 1, 2
            """;

    /** 기동 점검용: 캐시 엔티티 테이블의 지연 불가 유니크·EXCLUDE 제약(PK 제외). */
    private static final String FIND_NOT_DEFERRABLE = """
            select r.relname, c.conname
              from pg_catalog.pg_constraint c
              join pg_catalog.pg_class r on r.oid = c.conrelid
             where c.contype in ('u', 'x')
               and not c.condeferrable
               and r.relname = any (?)
               and pg_catalog.pg_table_is_visible(r.oid)
             order by 1, 2
            """;

    private static final String POSTGRES = "PostgreSQL";

    private final List<AutoCacheRepository<?, ?, ?>> repositories;
    private final boolean enabled;

    @PersistenceContext
    private EntityManager entityManager;

    @PersistenceUnit
    private EntityManagerFactory entityManagerFactory;

    /** null 이면 아직 모름. 처음 확인할 때 JDBC 메타데이터로 정한다(문장을 실행하지 않는다). */
    private volatile Boolean postgres;

    private volatile String[] tableNames;

    public UniqueConstraintDeferral(List<AutoCacheRepository<?, ?, ?>> repositories, boolean enabled) {
        this.repositories = repositories == null ? List.of() : repositories;
        this.enabled = enabled;
    }

    /** 아무것도 하지 않는 인스턴스. 예전 생성자로 만든 {@link CacheSyncService} 가 쓴다. */
    public static UniqueConstraintDeferral disabled() {
        return new UniqueConstraintDeferral(List.of(), false);
    }

    /**
     * 현재 트랜잭션에서 캐시 엔티티 테이블의 지연 가능 유니크 제약을 커밋 때 검사하게 한다.
     * 동기화 트랜잭션 맨 앞(첫 쓰기 전)에서 불러야 한다. 실패해도 예외를 던지지 않고, 그 트랜잭션은 예전처럼
     * 문장마다 검사한다.
     */
    public void deferWithinCurrentTransaction(String rootId) {
        if (!enabled || entityManager == null || Boolean.FALSE.equals(postgres)) {
            return;
        }
        try {
            entityManager.unwrap(Session.class).doWork(connection -> defer(connection, rootId));
        } catch (RuntimeException e) {
            log.warn("[CacheSync] 유니크 제약 지연 실패, 이번 동기화는 문장마다 검사한다 rootId={}: {}", rootId,
                    e.getMessage());
        }
    }

    private void defer(Connection connection, String rootId) throws SQLException {
        if (!isPostgres(connection)) {
            return;
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            List<String> constraints = deferrableConstraints(connection);
            if (!constraints.isEmpty()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET CONSTRAINTS " + String.join(", ", constraints) + " DEFERRED");
                }
                log.debug("[CacheSync] 커밋 때 검사할 유니크 제약 rootId={}: {}", rootId, constraints);
            }
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            connection.rollback(savepoint);
            log.warn("[CacheSync] 유니크 제약 지연 실패, 이번 동기화는 문장마다 검사한다 rootId={}: {}", rootId,
                    e.getMessage());
        }
    }

    private List<String> deferrableConstraints(Connection connection) throws SQLException {
        List<String> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(FIND_DEFERRABLE)) {
            Array names = connection.createArrayOf("text", tableNames());
            statement.setArray(1, names);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    constraints.add(quote(rs.getString(1)) + "." + quote(rs.getString(2)));
                }
            }
        }
        return constraints;
    }

    private boolean isPostgres(Connection connection) throws SQLException {
        Boolean known = postgres;
        if (known == null) {
            known = POSTGRES.equals(connection.getMetaData().getDatabaseProductName());
            postgres = known;
            if (!known) {
                log.warn("[CacheSync] PostgreSQL 이 아니라 유니크 제약을 커밋 때 검사하게 할 수 없다. "
                        + "동기화 중 행 순서에 따라 유니크 제약에 걸릴 수 있다.");
            }
        }
        return known;
    }

    /** 캐시 엔티티의 테이블 이름. 따옴표 없는 이름은 PostgreSQL 이 소문자로 접으므로 소문자도 함께 넣는다. */
    private String[] tableNames() {
        String[] names = tableNames;
        if (names == null) {
            Set<String> set = new LinkedHashSet<>();
            for (AutoCacheRepository<?, ?, ?> repository : repositories) {
                String table = ReflectionSupport.getTableName(repository.getEntityType());
                set.add(table);
                set.add(table.toLowerCase(Locale.ROOT));
            }
            names = set.toArray(String[]::new);
            tableNames = names;
        }
        return names;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * 기동 점검: 캐시 엔티티 테이블에 지연 불가 유니크 제약이 있으면 알린다. 동작은 바꾸지 않는다.
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!enabled || entityManagerFactory == null || repositories.isEmpty()) {
            return;
        }
        EntityManager em = null;
        try {
            em = entityManagerFactory.createEntityManager();
            em.unwrap(Session.class).doWork(this::warnNotDeferrable);
        } catch (RuntimeException e) {
            log.warn("[CacheSync] 유니크 제약 점검 실패: {}", e.getMessage());
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    private void warnNotDeferrable(Connection connection) throws SQLException {
        if (!isPostgres(connection)) {
            return;
        }
        List<String> found = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(FIND_NOT_DEFERRABLE)) {
            statement.setArray(1, connection.createArrayOf("text", tableNames()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString(1) + "." + rs.getString(2));
                }
            }
        }
        if (!found.isEmpty()) {
            log.warn("[CacheSync] 지연 불가 유니크 제약 {}개: {}. 동기화 중 행을 쓰는 순서에 따라 최종 상태가 정상이어도 "
                    + "이 제약에 걸릴 수 있다. DEFERRABLE INITIALLY IMMEDIATE 로 선언하면 동기화 때만 커밋 시 검사한다.",
                    found.size(), found);
        }
    }
}
