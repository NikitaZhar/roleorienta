package com.roleorienta.worker.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private static final String VACANCY_URL = "https://a.qq/job";
    private static final LocalDate DAY = LocalDate.parse("2026-10-18");

    /** Сутки — в часовом поясе показателей (Europe/Bratislava, летнее время UTC+2): 2026-10-17T22:00Z–18T22:00Z. */
    private static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");

    @Autowired
    private MetricsRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Компании: A — подключена (Workday), сайт подтверждён брендом; B — проверена за день, сайта нет; F — не проверена,
     * но сайт найден (в «с сайтом» не входит — не обработана); C — появилась после дня; D — прекратила деятельность
     * до дня. Источники: S1 (страна — от компании A) прочитан за день дважды (отказ, затем полностью); S2 не читался,
     * S3 создан за 2 часа до конца дня (ещё не просрочен). Вакансии: новая актуальная в QQ (23:00 UTC 17-го — уже
     * 18-е по Братиславе), новая следующего дня (23:00 UTC 18-го — 19-е), закрытая за день в QQ и «весь мир», на
     * перепроверке без страны. Одно задание не выполнено за день.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM core_metric_daily");
        jdbcTemplate.update("DELETE FROM job_posting WHERE external_id = 'qq-coded'");
        jdbcTemplate.update("DELETE FROM crawl_run WHERE source_id IN (SELECT id FROM source WHERE board LIKE 'qq-%')");
        jdbcTemplate.update(
                "DELETE FROM company_source WHERE source_id IN (SELECT id FROM source WHERE board LIKE 'qq-%')");
        jdbcTemplate.update("DELETE FROM company_site WHERE company_id IN (SELECT id FROM company WHERE country = ?)",
                COUNTRY);
        jdbcTemplate.update("DELETE FROM company_check WHERE company_id IN (SELECT id FROM company WHERE country = ?)",
                COUNTRY);
        jdbcTemplate.update("DELETE FROM source WHERE board LIKE 'qq-%'");
        jdbcTemplate.update("DELETE FROM company WHERE country = ?", COUNTRY);
        jdbcTemplate.update("DELETE FROM vacancy WHERE primary_url = ?", VACANCY_URL);
        jdbcTemplate.update("DELETE FROM task WHERE type = ?", TASK_TYPE);
        long companyA = company("1", "2026-10-01T00:00:00Z", null);
        long companyB = company("2", "2026-10-01T00:00:00Z", null);
        company("3", "2026-10-20T00:00:00Z", null);
        company("4", "2026-10-01T00:00:00Z", LocalDate.parse("2026-10-10"));
        long companyF = company("5", "2026-10-01T00:00:00Z", null);
        check(companyA, "CONNECTED", "2026-10-05T00:00:00Z");
        check(companyB, "SITE_NOT_FOUND", "2026-10-18T10:00:00Z");
        site(companyA, "a.qq");
        site(companyF, "f.qq");
        long sourceA = jdbcTemplate.queryForObject(
                "INSERT INTO source (provider, board) VALUES ('workday', 'qq-a') RETURNING id", Long.class);
        source("qq-b", "2026-10-01T00:00:00Z");
        source("qq-c", "2026-10-18T20:00:00Z");
        jdbcTemplate.update("INSERT INTO company_source (company_id, source_id, role) VALUES (?, ?, 'EMPLOYER')",
                companyA, sourceA);
        jdbcTemplate.update("""
                INSERT INTO crawl_run (source_id, started_at, finished_at, state, failure_kind, postings_count)
                VALUES (?, ?, ?, 'FAILED', 'TEMPORARY', 0)""",
                sourceA, at("2026-10-18T10:00:00Z"), at("2026-10-18T10:01:00Z"));
        jdbcTemplate.update("""
                INSERT INTO crawl_run (source_id, started_at, finished_at, state, postings_count)
                VALUES (?, ?, ?, 'COMPLETE', 3)""", sourceA, at("2026-10-18T12:00:00Z"), at("2026-10-18T12:01:00Z"));
        vacancy("ACTIVE", "ARRAY['QQ']", "2026-10-17T23:00:00Z", null);
        vacancy("ACTIVE", "ARRAY['QQ']", "2026-10-18T23:00:00Z", null);
        vacancy("CLOSED", "ARRAY['QQ', '*']", "2026-10-01T00:00:00Z", "2026-10-18T09:00:00Z");
        vacancy("NEEDS_RECHECK", "NULL", "2026-10-01T00:00:00Z", null);
        jdbcTemplate.update("""
                INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, first_seen_at, last_confirmed_at,
                                         occupation_code)
                SELECT ?, id, 'qq-coded', title, primary_url, now(), now(), '2512' FROM vacancy
                WHERE primary_url = ? AND state = 'ACTIVE' ORDER BY id LIMIT 1""", sourceA, VACANCY_URL);
        jdbcTemplate.update("""
                INSERT INTO task (type, task_key, payload, state, updated_at)
                VALUES (?, 'metrics-test:failed', '{}', 'FAILED', ?)""", TASK_TYPE, at("2026-10-18T05:00:00Z"));
    }

    /**
     * Показатели дня по данным {@link #setUp()}; повторный расчёт дня заменяет строки, а не дублирует.
     */
    @Test
    void computesDayMetrics() {
        Instant start = DAY.atStartOfDay(ZONE).toInstant();
        Instant end = DAY.plusDays(1).atStartOfDay(ZONE).toInstant();
        repository.replaceDay(DAY, start, end);
        repository.replaceDay(DAY, start, end);

        assertThat(value(COUNTRY, "companies_known", "")).isEqualTo(3);
        assertThat(value(COUNTRY, "companies_processed", "")).isEqualTo(2);
        assertThat(value(COUNTRY, "companies_remaining", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_with_site", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_with_site", "BRAND")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_connected", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_connected", "workday")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_failed", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "companies_failed", "SITE_NOT_FOUND")).isEqualTo(1);
        assertThat(value(COUNTRY, "vacancies_active", "")).isEqualTo(2);
        assertThat(value(COUNTRY, "vacancies_recognized", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "vacancies_new", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "vacancies_closed", "")).isEqualTo(1);
        assertThat(value("*", "vacancies_closed", "")).isPositive();
        assertThat(value("", "vacancies_recheck", "")).isPositive();
        assertThat(value(COUNTRY, "sources", "")).isEqualTo(3);
        assertThat(value(COUNTRY, "sources", "OK")).isEqualTo(3);
        assertThat(value(COUNTRY, "source_reads", "")).isEqualTo(1);
        assertThat(value(COUNTRY, "source_reads", "COMPLETE")).isEqualTo(1);
        assertThat(value(COUNTRY, "source_reads", "FAILED:TEMPORARY")).isZero();
        assertThat(value(COUNTRY, "sources_overdue", "")).isEqualTo(1);
        assertThat(value("", "operations_failed", TASK_TYPE)).isEqualTo(1);
        assertThat(value("", "operations_failed", "")).isPositive();
        assertThat(repository.computedDays(DAY)).contains(DAY);
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

    private void source(String board, String createdAt) {
        jdbcTemplate.update("INSERT INTO source (provider, board, country, created_at) VALUES ('greenhouse', ?, ?, ?)",
                board, COUNTRY, at(createdAt));
    }

    private void site(long companyId, String host) {
        jdbcTemplate.update("""
                INSERT INTO company_site (company_id, host, evidence_url, source, status, proof, found_at)
                VALUES (?, ?, ?, 'COMMON_CRAWL', 'FOUND', 'BRAND', ?)""",
                companyId, host, "https://" + host + "/", at("2026-10-02T00:00:00Z"));
    }

    private void vacancy(String state, String countries, String firstSeen, String closed) {
        jdbcTemplate.update("INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, "
                + "closed_at, work_countries) VALUES (?, 'Java Developer', ?, ?, ?, ?, "
                + countries + ")", state, VACANCY_URL, at(firstSeen), at(firstSeen),
                closed == null ? null : at(closed));
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
