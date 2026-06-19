package com.sharedsync.shared.repository.itest;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.sharedsync.shared.repository.CacheStore;
import com.sharedsync.shared.repository.InMemoryCacheStore;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * AutoCacheRepository 의 DB 경로(EntityDtoConverter / DatabaseReader / DatabaseWriter)를
 * 실제 PostgreSQL 에 대해 검증하는 통합 테스트.
 *
 * <p>전제: localhost:5432/sharedsync_test (postgres/postgres) 사용 가능. 없으면 자동 스킵.
 * 캐시 저장소는 결정적 검증을 위해 InMemoryCacheStore(globalCacheStore 빈)를 사용한다.
 * 스키마는 ddl-auto=create-drop 로 매 실행 생성/삭제한다.</p>
 */
@EnabledIf("dbAvailable")
@SpringBootTest(classes = AutoCacheRepositoryDbIntegrationTest.TestConfig.class, properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/sharedsync_test",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.open-in-view=false"
})
@Transactional
class AutoCacheRepositoryDbIntegrationTest {

    static final String URL = "jdbc:postgresql://localhost:5432/sharedsync_test";

    static boolean dbAvailable() {
        try (Connection c = DriverManager.getConnection(URL, "postgres", "postgres")) {
            return c != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private WidgetRepository repo;

    @PersistenceContext
    private EntityManager em;

    @Test
    void syncToDatabaseByDto_convertsAndPersists_thenRoundTripsViaLoad() {
        UUID id = UUID.randomUUID();
        WidgetDto dto = new WidgetDto();
        dto.setId(id);
        dto.setName("hello-db");

        // DatabaseWriter.saveToDatabase + EntityDtoConverter.convertToEntity 경로
        WidgetDto saved = repo.syncToDatabaseByDto(dto);
        assertThat(saved).isNotNull();

        // 실제 DB에 행이 들어갔는지 EntityManager 로 직접 확인
        em.flush();
        Widget row = em.find(Widget.class, id);
        assertThat(row).isNotNull();
        assertThat(row.getName()).isEqualTo("hello-db");

        // DatabaseReader.loadFromDatabaseById + EntityDtoConverter.convertToDto 경로
        WidgetDto loaded = repo.loadFromDatabaseById(id);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getName()).isEqualTo("hello-db");
    }

    @Test
    void syncToDatabaseByDto_existingRow_isMergedNotDuplicated() {
        UUID id = UUID.randomUUID();
        WidgetDto first = new WidgetDto();
        first.setId(id);
        first.setName("v1");
        repo.syncToDatabaseByDto(first);
        em.flush();

        WidgetDto second = new WidgetDto();
        second.setId(id);
        second.setName("v2");
        repo.syncToDatabaseByDto(second);
        em.flush();
        em.clear();

        Widget row = em.find(Widget.class, id);
        assertThat(row.getName()).isEqualTo("v2");

        List<Widget> all = em.createQuery("select w from Widget w where w.id = :id", Widget.class)
                .setParameter("id", id)
                .getResultList();
        assertThat(all).hasSize(1); // merge 이므로 중복 INSERT 없음
    }

    @Configuration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @EntityScan(basePackageClasses = Widget.class)
    static class TestConfig {

        @Bean(name = "globalCacheStore")
        CacheStore<WidgetDto> globalCacheStore() {
            return new InMemoryCacheStore<>();
        }

        @Bean
        WidgetRepository widgetRepository() {
            return new WidgetRepository();
        }
    }
}
