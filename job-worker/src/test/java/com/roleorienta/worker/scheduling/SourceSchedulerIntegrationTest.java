package com.roleorienta.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * Интеграционный тест планировщика источников на реальном PostgreSQL
 * (Testcontainers). Проверяет: активный источник планируется (создаются обход,
 * задание и outbox-событие); повторный проход в том же окне не создаёт дублей
 * (идемпотентность по ключу «источник + окно»); неактивные источники не планируются.
 *
 * <p>Схема создаётся фикстурой {@code /db/scheduler-schema.sql}. Тик планировщика
 * и публикатора отключены профилем {@code test}; проход запускается вручную.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Sql(scripts = "/db/scheduler-schema.sql")
class SourceSchedulerIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SourceScheduler scheduler;

    @Test
    void schedulesActiveSourceOncePerWindow() {
        seedSource("greenhouse", "acme", "ACTIVE");

        boolean leader = scheduler.runOnce();

        assertThat(leader).isTrue();
        assertThat(count("crawl_run")).isEqualTo(1L);
        assertThat(count("crawl_task")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);

        // Повторный проход в том же окне не создаёт дублей.
        scheduler.runOnce();

        assertThat(count("crawl_run")).isEqualTo(1L);
        assertThat(count("crawl_task")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
    }

    @Test
    void doesNotScheduleInactiveSource() {
        seedSource("lever", "beta", "PAUSED");

        scheduler.runOnce();

        assertThat(count("crawl_run")).isEqualTo(0L);
        assertThat(count("crawl_task")).isEqualTo(0L);
        assertThat(count("outbox_event")).isEqualTo(0L);
    }

    /**
     * Создаёт провайдера и источник в заданном состоянии.
     */
    private void seedSource(String providerCode, String externalRef, String state) {
        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO provider (code, display_name, kind) VALUES (?, ?, 'ATS') RETURNING id",
                Long.class, providerCode, providerCode);
        jdbcTemplate.update(
                "INSERT INTO source (provider_id, kind, external_ref, base_url, state) "
                        + "VALUES (?, 'COMPANY_BOARD', ?, ?, ?)",
                providerId, externalRef, "https://example.test/" + externalRef, state);
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }
}
