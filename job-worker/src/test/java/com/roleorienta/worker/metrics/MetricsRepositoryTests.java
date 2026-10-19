package com.roleorienta.worker.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Показатели ядра за день (§131) на PostgreSQL из Testcontainers. Данные теста — в выдуманной стране {@code QQ} и
 * с типом задания {@value #TASK_TYPE}: строки других тестов в общей БД на них не влияют.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MetricsRepositoryTests {

    private static final String COUNTRY = "QQ";
    private static final String TASK_TYPE = "METRICS_TEST";
    private static final LocalDate DAY = LocalDate.parse("2026-10-18");
    private static final Instant START = Instant.parse("2026-10-18T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-19T00:00:00Z");

    @Autowired
    private MetricsRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Компании: A — подключена (Workday), сайт подтверждён брендом; B — проверена после конца дня, сайта нет;
     * C — появилась после дня; D — прекратила деятельность до дня. Источники: S1 (страна — от компании A) прочитан
     * за день дважды (отказ, затем полностью); S2 не читался. Вакансии: новая актуальная в QQ, закрытая за день в QQ
     * и «весь мир», на перепроверке без страны. Одно задание не выполнено за день.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM core_metric_daily");
        long companyA = company("1", "2026-10-01T00:00:00Z", null);
        long companyB = company("2", "2026-10-01T00:00:00Z", null);
        company("3", "2026-10-20T00:00:00Z", null);
        company("4", "2026-10-01T00:00:00Z", LocalDate.parse("2026-10-10"));
        check(companyA, "CONNECTED", "2026-10-05T00:00:00Z");
        check(companyB, "SITE_NOT_FOUND", "2026-10-19T10:00:00Z");
        jdbcTemplate.update("""
                INSERT INTO company_site (company_id, host, evidence_url, source, status, proof, found_at)
                VALUES (?, 'a.qq', 'https://a.qq/', 'COMMON_CRAWL', 'FOUND', 'BRAND', ?)""",
                companyA, at("2026-10-02T00:00:00Z"));
        long sourceA = jdbcTemplate.queryForObject(
                "INSERT INTO source (provider, board) VALUES ('workday', 'qq-a') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO source (provider, board, country) VALUES ('greenhouse', 'qq-b', ?)", COUNTRY);
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id, role) VALUES (?, ?, 'EMPLOYER')",
                companyA, sourceA);
        jdbcTemplate.update("""
                INSERT INTO crawl_run (source_id, started_at, finished_at, state, failure_kind, postings_count)
                VALUES (?, ?, ?, 'FAILED', 'TEMPORARY', 0)""",
                sourceA, at("2026-10-18T10:00:00Z"), at("2026-10-18T10:01:00Z"));
        jdbcTemplate.update("""
                INSERT INTO crawl_run (source_id, started_at, finished_at, state, postings_count)
                VALUES (?, ?, ?, 'COMPLETE', 3)""", sourceA, at("2026-10-18T12:00:00Z"), at("2026-10-18T12:01:00Z"));
        vacancy("ACTIVE", "ARRAY['QQ']", "2026-10-18T08:00:00Z", null);
        vacancy("CLOSED", "ARRAY['QQ', '*']", "2026-10-01T00:00:00Z", "2026-10-18T09:00:00Z");
        vacancy("NEEDS_RECHECK", "NULL", "2026-10-01T00:00:00Z", null);
        jdbcTemplate.update("""
                INSERT INTO task (type, task_key, payload, state, updated_at)
                VALUES (?, 'metrics-test:failed', '{}', 'FAILED', ?)""", TASK_TYPE, at("2026-10-18T05:00:00Z"));
    }

    /**
     * Показатели дня по данным {@link #setUp()}; повторный расчёт дня заменяет строки, а не дублирует.
     */
    @Test
    void computesDayMetrics() {
        repository.replaceDay(DAY, START, END);
        repository.replaceDay(DAY, START, END);

        assertThat(value(COUNTRY, "companies_known", "")).isEqualTo(2);
        assertThat(value(COUNTRY, "companies_processed", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_remaining", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_with_site", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_with_site", "BRAND")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_connected", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_connected", "workday")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_failed", "SITE_NOT_FOUND")).isEqualTo(1);
        assertThat(value(COUNTRY, "vacancies_active", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "vacancies_new", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "vacancies_closed", "")).isEqualTo(1);
        assertThat(value("*", "vacancies_closed", "")).isPositive();
        assertThat(value("", "vacancies_recheck", "")).isPositive();
        assertThat(value(COUNTRY, "sources", "")).isEqualTo(2);
        assertThat(value(COUNTRY, "sources", "OK")).isEqualTo(2);
        assertThat(value(COUNTRY, "source_reads", "COMPLETE")).isEqualTo(1);
        assertThat(value(COUNTRY, "source_reads", "FAILED:TEMPORARY")).isZero();
        assertThat(value(COUNTRY, "sources_overdue", "")).isEqualTo(1);
        assertThat(value("", "operations_failed", TASK_TYPE)).isEqualTo(1);
    }

    private long company(String number, String firstSeen, LocalDate terminatedOn) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO company (country, registration_number, name, registry, terminated_on, first_seen_at)
                VALUES (?, ?, ?, 'TEST', ?, ?) RETURNING id""", Long.class,
                COUNTRY, "QQ-" + number, "Company " + number, terminatedOn, at(firstSeen));
    }

    private void check(long companyId, String result, String firstChecked) {
        jdbcTemplate.update("""
                INSERT INTO company_check (company_id, result, first_checked_at, checked_at) VALUES (?, ?, ?, ?)""",
                companyId, result, at(firstChecked), at(firstChecked));
    }

    private void vacancy(String state, String countries, String firstSeen, String closed) {
        jdbcTemplate.update("INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, "
                + "closed_at, work_countries) VALUES (?, 'Java Developer', 'https://a.qq/job', ?, ?, ?, "
                + countries + ")", state, at(firstSeen), at(firstSeen), closed == null ? null : at(closed));
    }

    private long value(String country, String metric, String dimension) {
        Long value = jdbcTemplate.query("""
                SELECT value FROM core_metric_daily WHERE day = ? AND country = ? AND metric = ? AND dimension = ?""",
                rows -> rows.next() ? rows.getLong(1) : 0L, DAY, country, metric, dimension);
        return value == null ? 0 : value;
    }

    private static OffsetDateTime at(String instant) {
        return Instant.parse(instant).atOffset(ZoneOffset.UTC);
    }
}
