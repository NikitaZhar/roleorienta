package com.roleorienta.worker.metrics;

import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Показатели ядра за день в {@code core_metric_daily} (бизнес-описание §7.3; технический документ §11; §131) —
 * SQL-агрегаты по хранимым фактам. Строка — день, страна, показатель, разрез ({@code ''} — итог), значение.
 *
 * <p>Показатели-события (новые и закрытые вакансии, чтения и неудачные операции за день) и показатели компаний
 * (по датам появления и первой проверки) считаются по датам и пересчитываются за любой день. Показатели-состояния
 * (итог проверки компании, подтверждённость сайта, состояние вакансии, доступность источника) — текущие на момент
 * расчёта: день считается вскоре после своего конца, задним числом их не восстановить.</p>
 *
 * <p>Параметры запросов приведены к типу явно ({@code CAST(? AS date)}): в списке выборки {@code INSERT … SELECT}
 * PostgreSQL иначе не знает тип параметра.</p>
 */
@Repository
public class MetricsRepository {

    /** Источник не читался дольше — чтение просрочено (бизнес-описание §4.6: не реже раза в сутки). */
    static final Duration READ_OVERDUE_AFTER = Duration.ofHours(24);

    /**
     * Компании страны: известно (действующие на день), обработано (первая проверка до конца дня), осталось.
     */
    private static final String COMPANIES = """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), counted.country, kind.metric, '', kind.value
            FROM (SELECT co.country, count(*) AS known, count(ch.company_id) AS processed
                  FROM company co
                  LEFT JOIN company_check ch ON ch.company_id = co.id AND ch.first_checked_at < CAST(? AS timestamptz)
                  WHERE co.first_seen_at < CAST(? AS timestamptz)
                    AND (co.terminated_on IS NULL OR co.terminated_on > CAST(? AS date))
                  GROUP BY co.country) counted
            CROSS JOIN LATERAL (VALUES ('companies_known', counted.known),
                                       ('companies_processed', counted.processed),
                                       ('companies_remaining', counted.known - counted.processed)) kind (metric, value)
            """;

    /** Компании с подтверждённым сайтом ({@code FOUND}): итог и по способу подтверждения. */
    private static final String SITES = """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), co.country, 'companies_with_site',
                   CASE WHEN GROUPING(site.proof) = 1 THEN '' ELSE coalesce(site.proof, 'UNKNOWN') END,
                   count(DISTINCT co.id)
            FROM company co
            JOIN company_site site ON site.company_id = co.id AND site.status = 'FOUND'
                                      AND site.found_at < CAST(? AS timestamptz)
            GROUP BY GROUPING SETS ((co.country), (co.country, site.proof))
            """;

    /**
     * Компании с подключённым каналом: итог и по системе найма своих источников (роль работодателя или группы;
     * компания с источниками разных систем считается в каждой — сумма разрезов может быть больше итога).
     */
    private static final String CONNECTED = """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), co.country, 'companies_connected',
                   CASE WHEN GROUPING(s.provider) = 1 THEN '' ELSE coalesce(s.provider, 'NONE') END,
                   count(DISTINCT co.id)
            FROM company co
            JOIN company_check ch ON ch.company_id = co.id AND ch.result = 'CONNECTED'
            LEFT JOIN company_source cs ON cs.company_id = co.id AND cs.role IN ('EMPLOYER', 'GROUP')
            LEFT JOIN source s ON s.id = cs.source_id
            GROUP BY GROUPING SETS ((co.country), (co.country, s.provider))
            """;

    /** Неудачи определения канала — по причине (итог проверки компании). */
    private static final String FAILED_CHECKS = """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), co.country, 'companies_failed', ch.result, count(*)
            FROM company co JOIN company_check ch ON ch.company_id = co.id AND ch.result <> 'CONNECTED'
            GROUP BY co.country, ch.result
            """;

    /**
     * Вакансии по стране работы (вакансия нескольких стран — в каждой; без страны — {@code ''}; весь мир —
     * {@code '*'}): актуальные и на перепроверке (сейчас), новые и закрытые за день.
     */
    private static final String VACANCIES = """
            WITH placed AS (
                SELECT v.state, v.first_seen_at, v.closed_at, coalesce(place.country, '') AS country
                FROM vacancy v
                LEFT JOIN LATERAL (SELECT DISTINCT unnest(v.work_countries) AS country) place ON TRUE)
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), counted.country, counted.metric, '', count(*)
            FROM (SELECT country, CASE state WHEN 'ACTIVE' THEN 'vacancies_active' ELSE 'vacancies_recheck' END
                         AS metric
                  FROM placed WHERE state <> 'CLOSED'
                  UNION ALL
                  SELECT country, 'vacancies_new' FROM placed
                  WHERE first_seen_at >= CAST(? AS timestamptz) AND first_seen_at < CAST(? AS timestamptz)
                  UNION ALL
                  SELECT country, 'vacancies_closed' FROM placed
                  WHERE closed_at >= CAST(? AS timestamptz) AND closed_at < CAST(? AS timestamptz)) counted
            GROUP BY counted.country, counted.metric
            """;

    /**
     * Страна источника: своя страна сбора или страна его компании; иначе {@code ''}.
     */
    private static final String SOURCE_COUNTRY = """
            WITH placed AS (
                SELECT s.id, s.provider, s.availability,
                       coalesce(CAST(s.country AS text),
                                (SELECT min(CAST(co.country AS text)) FROM company_source cs
                                 JOIN company co ON co.id = cs.company_id WHERE cs.source_id = s.id), '') AS country
                FROM source s)
            """;

    /** Источники по доступности (сейчас): итог и по {@code OK / TEMP_FAILING / UNAVAILABLE}. */
    private static final String SOURCES = SOURCE_COUNTRY + """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), country, 'sources',
                   CASE WHEN GROUPING(availability) = 1 THEN '' ELSE availability END, count(*)
            FROM placed
            GROUP BY GROUPING SETS ((country), (country, availability))
            """;

    /**
     * Источники по итогу последнего за день чтения: {@code COMPLETE}, {@code PARTIAL:<причина>},
     * {@code FAILED:<вид отказа>}.
     */
    private static final String SOURCE_READS = SOURCE_COUNTRY + """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), last_read.country, 'source_reads', last_read.outcome, count(*)
            FROM (SELECT DISTINCT ON (r.source_id) placed.country,
                         CASE r.state WHEN 'COMPLETE' THEN 'COMPLETE'
                                      WHEN 'PARTIAL' THEN 'PARTIAL:' || r.partial_reason
                                      ELSE 'FAILED:' || r.failure_kind END AS outcome
                  FROM crawl_run r JOIN placed ON placed.id = r.source_id
                  WHERE r.finished_at >= CAST(? AS timestamptz) AND r.finished_at < CAST(? AS timestamptz)
                  ORDER BY r.source_id, r.finished_at DESC) last_read
            GROUP BY last_read.country, last_read.outcome
            """;

    /**
     * Источники с просроченным чтением: доступные (не «недоступен»), читаемые заданием чтения (без источников
     * государственного портала — их вакансии пишутся общим списком, §107), без чтения, начатого за
     * {@link #READ_OVERDUE_AFTER} до конца дня.
     */
    private static final String SOURCES_OVERDUE = SOURCE_COUNTRY + """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), country, 'sources_overdue', '', count(*)
            FROM placed
            WHERE availability <> 'UNAVAILABLE' AND provider <> ?
              AND NOT EXISTS (SELECT 1 FROM crawl_run r WHERE r.source_id = placed.id
                              AND r.started_at >= CAST(? AS timestamptz) AND r.started_at < CAST(? AS timestamptz))
            GROUP BY country
            """;

    /** Операции, не выполненные после всех попыток (задания {@code FAILED} за день), — по типу задания. */
    private static final String FAILED_OPERATIONS = """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), '', 'operations_failed', t.type, count(*)
            FROM task t
            WHERE t.state = 'FAILED'
              AND t.updated_at >= CAST(? AS timestamptz) AND t.updated_at < CAST(? AS timestamptz)
            GROUP BY t.type
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public MetricsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Пересчитывает показатели дня: прежние строки дня удаляются, новые пишутся в той же транзакции — показатели
     * дня видны либо прежние, либо новые целиком.
     *
     * @param day   день
     * @param start начало дня (в часовом поясе показателей)
     * @param end   конец дня — начало следующего
     */
    @Transactional
    public void replaceDay(LocalDate day, Instant start, Instant end) {
        OffsetDateTime from = utc(start);
        OffsetDateTime to = utc(end);
        jdbcTemplate.update("DELETE FROM core_metric_daily WHERE day = ?", day);
        jdbcTemplate.update(COMPANIES, day, to, to, day);
        jdbcTemplate.update(SITES, day, to);
        jdbcTemplate.update(CONNECTED, day);
        jdbcTemplate.update(FAILED_CHECKS, day);
        jdbcTemplate.update(VACANCIES, day, from, to, from, to);
        jdbcTemplate.update(SOURCES, day);
        jdbcTemplate.update(SOURCE_READS, day, from, to);
        jdbcTemplate.update(SOURCES_OVERDUE, day, StatePortalAdapter.PROVIDER, utc(end.minus(READ_OVERDUE_AFTER)), to);
        jdbcTemplate.update(FAILED_OPERATIONS, day, from, to);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
