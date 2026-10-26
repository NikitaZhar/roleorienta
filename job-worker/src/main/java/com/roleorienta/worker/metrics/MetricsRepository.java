package com.roleorienta.worker.metrics;

import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
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

    /**
     * Обработанные компании дня — те же, что в {@code companies_processed} ({@link #COMPANIES}): действующие на день,
     * появившиеся и впервые проверенные до конца дня, с текущим итогом проверки. Показатели сайта, подключения и
     * неудач берутся только из них — доля от «обработано» не больше 100 % (аудит §133). Параметры: конец дня, конец
     * дня, день.
     */
    private static final String PROCESSED = """
            WITH processed AS (
                SELECT co.id, co.country, ch.result
                FROM company co JOIN company_check ch ON ch.company_id = co.id
                WHERE ch.first_checked_at < CAST(? AS timestamptz) AND co.first_seen_at < CAST(? AS timestamptz)
                  AND (co.terminated_on IS NULL OR co.terminated_on > CAST(? AS date)))
            """;

    /** Обработанные компании с подтверждённым сайтом ({@code FOUND} до конца дня): итог и по способу подтверждения. */
    private static final String SITES = PROCESSED + """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), processed.country, 'companies_with_site',
                   CASE WHEN GROUPING(site.proof) = 1 THEN '' ELSE site.proof END, count(DISTINCT processed.id)
            FROM processed
            JOIN company_site site ON site.company_id = processed.id AND site.status = 'FOUND'
                                      AND site.found_at < CAST(? AS timestamptz)
            GROUP BY GROUPING SETS ((processed.country), (processed.country, site.proof))
            """;

    /**
     * Компании с подключённым каналом: итог и по системе найма своих источников (роль работодателя или группы;
     * компания с источниками разных систем считается в каждой — сумма разрезов может быть больше итога).
     */
    private static final String CONNECTED = PROCESSED + """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), processed.country, 'companies_connected',
                   CASE WHEN GROUPING(s.provider) = 1 THEN '' ELSE coalesce(s.provider, 'NONE') END,
                   count(DISTINCT processed.id)
            FROM processed
            LEFT JOIN company_source cs ON cs.company_id = processed.id AND cs.role IN ('EMPLOYER', 'GROUP')
            LEFT JOIN source s ON s.id = cs.source_id
            WHERE processed.result = 'CONNECTED'
            GROUP BY GROUPING SETS ((processed.country), (processed.country, s.provider))
            """;

    /** Обработанные компании, канал которых не найден: итог и по причине (итог проверки). */
    private static final String FAILED_CHECKS = PROCESSED + """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), country, 'companies_failed',
                   CASE WHEN GROUPING(result) = 1 THEN '' ELSE result END, count(*)
            FROM processed
            WHERE result <> 'CONNECTED'
            GROUP BY GROUPING SETS ((country), (country, result))
            """;

    /**
     * Вакансии по стране работы (вакансия нескольких стран — в каждой; без страны — {@code ''}; весь мир —
     * {@code '*'}): актуальные и на перепроверке (сейчас), новые и закрытые за день; открытые с распознанной профессией
     * (§206: сопоставлена позиции словаря или у публикации есть код группы ISCO-08 — то, что видит выбор позиции).
     */
    private static final String VACANCIES = """
            WITH placed AS (
                SELECT v.state, v.first_seen_at, v.closed_at, coalesce(place.country, '') AS country,
                       EXISTS (SELECT 1 FROM vacancy_position_match m WHERE m.vacancy_id = v.id)
                       OR EXISTS (SELECT 1 FROM job_posting p WHERE p.vacancy_id = v.id AND p.occupation_code IS NOT NULL)
                       AS recognized
                FROM vacancy v
                LEFT JOIN LATERAL (SELECT DISTINCT unnest(v.work_countries) AS country) place ON TRUE)
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), counted.country, counted.metric, '', count(*)
            FROM (SELECT country, CASE state WHEN 'ACTIVE' THEN 'vacancies_active' ELSE 'vacancies_recheck' END
                         AS metric
                  FROM placed WHERE state <> 'CLOSED'
                  UNION ALL
                  SELECT country, 'vacancies_recognized' FROM placed WHERE state <> 'CLOSED' AND recognized
                  UNION ALL
                  SELECT country, 'vacancies_new' FROM placed
                  WHERE first_seen_at >= CAST(? AS timestamptz) AND first_seen_at < CAST(? AS timestamptz)
                  UNION ALL
                  SELECT country, 'vacancies_closed' FROM placed
                  WHERE closed_at >= CAST(? AS timestamptz) AND closed_at < CAST(? AS timestamptz)) counted
            GROUP BY counted.country, counted.metric
            """;

    /**
     * Страна источника: своя страна сбора или страна его компании (работодателя или группы, не агентства); иначе
     * {@code ''}.
     */
    private static final String SOURCE_COUNTRY = """
            WITH placed AS (
                SELECT s.id, s.provider, s.availability, s.created_at,
                       coalesce(CAST(s.country AS text),
                                (SELECT min(CAST(co.country AS text)) FROM company_source cs
                                 JOIN company co ON co.id = cs.company_id
                                 WHERE cs.source_id = s.id AND cs.role IN ('EMPLOYER', 'GROUP')), '') AS country
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
     * Источники, читавшиеся за день: итог и по итогу последнего за день чтения — {@code COMPLETE},
     * {@code PARTIAL:<причина>}, {@code FAILED:<вид отказа>}.
     */
    private static final String SOURCE_READS = SOURCE_COUNTRY + """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), last_read.country, 'source_reads',
                   CASE WHEN GROUPING(last_read.outcome) = 1 THEN '' ELSE last_read.outcome END, count(*)
            FROM (SELECT DISTINCT ON (r.source_id) placed.country,
                         CASE r.state WHEN 'COMPLETE' THEN 'COMPLETE'
                                      WHEN 'PARTIAL' THEN 'PARTIAL:' || r.partial_reason
                                      ELSE 'FAILED:' || r.failure_kind END AS outcome
                  FROM crawl_run r JOIN placed ON placed.id = r.source_id
                  WHERE r.finished_at >= CAST(? AS timestamptz) AND r.finished_at < CAST(? AS timestamptz)
                  ORDER BY r.source_id, r.finished_at DESC) last_read
            GROUP BY GROUPING SETS ((last_read.country), (last_read.country, last_read.outcome))
            """;

    /**
     * Источники с просроченным чтением: доступные (не «недоступен»), читаемые заданием чтения (без источников
     * государственного портала — их вакансии пишутся общим списком, §107), созданные раньше чем за
     * {@link #READ_OVERDUE_AFTER} до конца дня и без чтения, начатого за это время. Параметры: день, провайдер портала,
     * начало окна, конец дня.
     */
    private static final String SOURCES_OVERDUE = SOURCE_COUNTRY + """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), country, 'sources_overdue', '', count(*)
            FROM placed
            WHERE availability <> 'UNAVAILABLE' AND provider <> ? AND created_at < CAST(? AS timestamptz)
              AND NOT EXISTS (SELECT 1 FROM crawl_run r WHERE r.source_id = placed.id
                              AND r.started_at >= CAST(? AS timestamptz) AND r.started_at < CAST(? AS timestamptz))
            GROUP BY country
            """;

    /** Операции, не выполненные после всех попыток (задания {@code FAILED} за день): итог и по типу задания. */
    private static final String FAILED_OPERATIONS = """
            INSERT INTO core_metric_daily (day, country, metric, dimension, value)
            SELECT CAST(? AS date), '', 'operations_failed',
                   CASE WHEN GROUPING(t.type) = 1 THEN '' ELSE t.type END, count(*)
            FROM task t
            WHERE t.state = 'FAILED'
              AND t.updated_at >= CAST(? AS timestamptz) AND t.updated_at < CAST(? AS timestamptz)
            GROUP BY GROUPING SETS ((), (t.type))
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
        jdbcTemplate.update(SITES, to, to, day, day, to);
        jdbcTemplate.update(CONNECTED, to, to, day, day);
        jdbcTemplate.update(FAILED_CHECKS, to, to, day, day);
        jdbcTemplate.update(VACANCIES, day, from, to, from, to);
        jdbcTemplate.update(SOURCES, day);
        jdbcTemplate.update(SOURCE_READS, day, from, to);
        OffsetDateTime overdueSince = utc(end.minus(READ_OVERDUE_AFTER));
        jdbcTemplate.update(SOURCES_OVERDUE, day, StatePortalAdapter.PROVIDER, overdueSince, overdueSince, to);
        jdbcTemplate.update(FAILED_OPERATIONS, day, from, to);
    }

    /**
     * @param from первый день
     * @return дни начиная с {@code from}, за которые показатели уже посчитаны
     */
    public Set<LocalDate> computedDays(LocalDate from) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT day FROM core_metric_daily WHERE day >= ?", LocalDate.class, from));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
