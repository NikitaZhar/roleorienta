package com.roleorienta.api.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Проверка миграции V30 (§85) — объединение дублей компаний, заведённых обнаружением до V28.
 *
 * <p>Схема накатывается Flyway до V29, в неё кладутся данные «как до V28» (у тенанта abcsupply —
 * компания с ключом и три дубля, у тенанта ace — один дубль, компания со связями с двумя
 * тенантами и компания без связей), затем применяется V30. Spring-контекст не нужен: миграция —
 * чистый SQL, поэтому тест работает напрямую с контейнером PostgreSQL.</p>
 */
@Testcontainers
class CompanyMergeMigrationIntegrationTest {

    /** Последняя версия схемы до проверяемой миграции. */
    private static final String VERSION_BEFORE_MERGE = "29";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;

    /**
     * Накатывает схему до V29, заполняет данные с дублями и применяет V30.
     */
    @BeforeAll
    static void migrateWithDuplicates() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target(VERSION_BEFORE_MERGE).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                INSERT INTO provider (code, display_name, kind) VALUES ('workday', 'Workday', 'ATS');
                INSERT INTO source (provider_id, kind, external_ref, base_url, state)
                SELECT 1, 'COMPANY_BOARD', ref, 'https://example.test', 'ACTIVE'
                FROM unnest(ARRAY['abcsupply/A', 'abcsupply/B', 'abcsupply/C', 'abcsupply/D',
                                  'ace/X', 'ace/Y', 'other/Z']) WITH ORDINALITY AS boards(ref, ord)
                ORDER BY ord;
                INSERT INTO company (name, identity_key) VALUES
                    ('ABC Supply', 'workday:abcsupply'), ('abcsupply/B', NULL), ('abcsupply/C', NULL),
                    ('abcsupply/D', NULL), ('Ace Hardware', 'workday:ace'), ('Ace Hardware Y', NULL),
                    ('Mixed', NULL), ('Manual', NULL);
                INSERT INTO company_source (source_id, company_id, verified_by, verified_at) VALUES
                    (1, 1, 'AUTO', now()), (2, 2, 'AUTO', now()), (3, 3, 'AUTO', now()),
                    (4, 4, 'AUTO', now()), (2, 3, 'AUTO', now()), (1, 2, 'AUTO', now()),
                    (5, 5, 'AUTO', now()), (6, 6, 'AUTO', now()), (3, 7, 'AUTO', now()),
                    (7, 7, 'AUTO', now());
                INSERT INTO app_user (email, password_hash, role) VALUES
                    ('first@example.test', 'hash', 'USER'), ('second@example.test', 'hash', 'USER');
                INSERT INTO company_subscription (app_user_id, company_id) VALUES
                    (1, 1), (1, 2), (1, 3), (2, 3), (2, 4), (2, 6), (1, 7);
                INSERT INTO job_posting (source_id, external_id, url, raw_title, first_seen_at, last_seen_at)
                VALUES (2, 'posting-1', 'https://example.test/1', 'Title', now(), now());
                INSERT INTO notification (app_user_id, job_posting_id, company_id, field_name)
                VALUES (1, 1, 2, 'title'), (2, 1, 4, 'title');
                INSERT INTO employer_candidate (provider_code, slug, base_url, state, confidence,
                                                company_id, source_id)
                VALUES ('workday', 'abcsupply/B', 'https://example.test', 'CONFIRMED', 'HIGH', 2, 2),
                       ('workday', 'ace/Y', 'https://example.test', 'CONFIRMED', 'HIGH', 6, 6);
                """);
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @Test
    void duplicatesAreDeletedOthersKept() {
        assertEquals(List.of(1L, 5L, 7L, 8L),
                jdbc.queryForList("SELECT id FROM company ORDER BY id", Long.class));
    }

    @Test
    void boardLinksMovedToKeyedCompanyWithoutRepeats() {
        assertEquals(List.of("1:1", "2:1", "3:1", "3:7", "4:1", "5:5", "6:5", "7:7"),
                jdbc.queryForList("SELECT source_id || ':' || company_id FROM company_source "
                        + "ORDER BY source_id, company_id", String.class));
    }

    @Test
    void subscriptionsMovedOnePerUserAndCompany() {
        assertEquals(List.of("1:1", "1:7", "2:1", "2:5"),
                jdbc.queryForList("SELECT app_user_id || ':' || company_id FROM company_subscription "
                        + "ORDER BY app_user_id, company_id", String.class));
    }

    @Test
    void notificationsAndCandidatesPointToKeyedCompany() {
        assertEquals(List.of(1L, 1L),
                jdbc.queryForList("SELECT company_id FROM notification ORDER BY id", Long.class));
        assertEquals(List.of(1L, 5L),
                jdbc.queryForList("SELECT company_id FROM employer_candidate ORDER BY id", Long.class));
    }
}
