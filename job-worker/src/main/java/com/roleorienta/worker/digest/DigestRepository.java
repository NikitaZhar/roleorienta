package com.roleorienta.worker.digest;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Данные сводки на почту (JDBC; стенограмма §164, аудит §165): кому пора, новые вакансии пользователя с отметкой
 * попавших в письмо, отметка отработанного дня.
 */
@Repository
public class DigestRepository {

    /**
     * Местное время пользователя в момент {@code ?} (первый параметр каждого использования): {@code AT TIME ZONE} даёт
     * время без пояса по часам пользователя; день недели ISO — понедельник 1, воскресенье 7.
     */
    private static final String LOCAL_NOW = "(CAST(? AS timestamptz) AT TIME ZONE u.time_zone)";

    /**
     * Новые вакансии пользователя для письма: выданы по его активным условиям, видны в результатах и ещё не были в
     * письме ({@code digest_vacancy}). «Видны» — те же правила, что у списка job-api ({@code VacancyRepository.VISIBLE},
     * меняются вместе): не закрыта, подтверждена не раньше срока, подходит хотя бы к одной позиции условий, стране и
     * формату, статус пользователя — «Новая» или «Просмотрена» (аудит §165). Параметры — пользователь и срок
     * подтверждения.
     */
    private static final String FRESH_FROM = """
             FROM search_condition c JOIN delivered_vacancy d ON d.search_condition_id = c.id
            JOIN vacancy v ON v.id = d.vacancy_id
            LEFT JOIN vacancy_user_status st ON st.user_id = c.user_id AND st.vacancy_id = v.id
            WHERE c.user_id = ? AND c.active
              AND v.state <> 'CLOSED' AND v.last_confirmed_at >= ?
              AND EXISTS (SELECT 1 FROM vacancy_position_match m
                          JOIN search_condition_position sp ON sp.position_id = m.position_id
                          WHERE m.vacancy_id = v.id AND sp.search_condition_id = c.id)
              AND (v.work_countries && c.countries OR cardinality(v.work_countries) = 0)
              AND (c.work_format IS NULL OR v.work_format IS NULL
                   OR v.work_format IN ('CONFLICT', 'HYBRID', c.work_format))
              AND (st.status IS NULL OR st.status = 'VIEWED')
              AND NOT EXISTS (SELECT 1 FROM digest_vacancy dv WHERE dv.user_id = c.user_id AND dv.vacancy_id = v.id)""";

    /** Работодатель вакансии — как в списке job-api ({@code VacancyRepository}): компания источника, иначе название у системы найма. */
    private static final String EMPLOYER = """
            coalesce((SELECT co.name FROM job_posting p
                      JOIN company_source cs ON cs.source_id = p.source_id AND cs.role IN ('EMPLOYER', 'GROUP')
                      JOIN company co ON co.id = cs.company_id WHERE p.vacancy_id = v.id
                      ORDER BY cs.role = 'GROUP', co.id LIMIT 1),
                     (SELECT s.employer_name FROM job_posting p JOIN source s ON s.id = p.source_id
                      WHERE p.vacancy_id = v.id AND s.employer_name IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM company_source cs WHERE cs.source_id = s.id)
                      ORDER BY s.id LIMIT 1))""";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public DigestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Кому пора: частота не «никогда», по часам пользователя наступило время письма, сегодняшний день подходит под
     * частоту (по будням — понедельник–пятница, раз в неделю — понедельник) и сводка за этот день ещё не отработана.
     *
     * @param now    сейчас
     * @param sendAt время письма по часам пользователя
     * @return пользователь и его сегодняшний день
     */
    public List<Due> due(Instant now, LocalTime sendAt) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        return jdbcTemplate.query("SELECT u.id, CAST(" + LOCAL_NOW + " AS date) AS day FROM user_account u "
                + "WHERE u.digest_frequency <> 'NEVER' AND CAST(" + LOCAL_NOW + " AS time) >= ? "
                + "AND (u.digest_day IS NULL OR u.digest_day < CAST(" + LOCAL_NOW + " AS date)) "
                + "AND (u.digest_frequency = 'DAILY' "
                + "OR (u.digest_frequency = 'WEEKDAYS' AND extract(isodow FROM " + LOCAL_NOW + ") <= 5) "
                + "OR (u.digest_frequency = 'WEEKLY' AND extract(isodow FROM " + LOCAL_NOW + ") = 1)) ORDER BY u.id",
                (row, number) -> new Due(row.getLong("id"), row.getObject("day", LocalDate.class)),
                at, at, sendAt, at, at, at);
    }

    /**
     * Блокирует строку пользователя до конца транзакции: одна сводка пользователя за раз (повтор задания ждёт и видит
     * отметку первой).
     *
     * @param userId пользователь
     * @return получатель; пусто — учётной записи нет
     */
    public Optional<Recipient> lockRecipient(long userId) {
        return jdbcTemplate.query("""
                SELECT email, language, digest_frequency, digest_day FROM user_account WHERE id = ? FOR UPDATE
                """, (row, number) -> new Recipient(row.getString("email"), row.getString("language"),
                row.getString("digest_frequency"), row.getObject("digest_day", LocalDate.class)), userId)
                .stream().findFirst();
    }

    /**
     * Отбирает новые вакансии письма и сразу отмечает их попавшими в письмо — одним запросом: изменяющий
     * {@code WITH … INSERT … RETURNING} работает на одном снимке данных, поэтому число и строки письма совпадают, а
     * выдача, зафиксированная позже, уйдёт в следующее письмо, а не потеряется (аудит §165). Отметка откатывается
     * вместе с транзакцией, если письмо не ушло. Число — по всем отобранным ({@code count(*) OVER ()} считается до
     * {@code LIMIT}); строки — открытые позже сверху.
     * https://www.postgresql.org/docs/16/queries-with.html#QUERIES-WITH-MODIFYING
     *
     * @param userId       пользователь
     * @param visibleSince подтверждены не раньше (срок без подтверждения)
     * @param now          момент письма — {@code mailed_at}
     * @param limit        строк не больше
     * @return сколько новых вакансий и первые строки; нет новых — 0 и пусто
     */
    public Fresh takeFresh(long userId, Instant visibleSince, Instant now, int limit) {
        List<Counted> rows = jdbcTemplate.query("WITH fresh AS (INSERT INTO digest_vacancy (user_id, vacancy_id, "
                + "mailed_at) SELECT DISTINCT c.user_id, v.id, CAST(? AS timestamptz)" + FRESH_FROM
                + " ON CONFLICT DO NOTHING RETURNING vacancy_id) "
                + "SELECT v.title, v.work_countries, v.posted_at, v.first_seen_at, " + EMPLOYER + " AS employer, "
                + "count(*) OVER () AS total FROM fresh f JOIN vacancy v ON v.id = f.vacancy_id "
                + "ORDER BY coalesce(v.posted_at, v.first_seen_at) DESC, v.id DESC LIMIT ?",
                (row, number) -> new Counted(row.getLong("total"), line(row)), utc(now), userId, utc(visibleSince),
                limit);
        return new Fresh(rows.isEmpty() ? 0 : rows.getFirst().total(), rows.stream().map(Counted::line).toList());
    }

    /**
     * Сводка за день отработана (письмо ушло или новых нет).
     *
     * @param userId пользователь
     * @param day    день по часам пользователя
     */
    public void markDone(long userId, LocalDate day) {
        jdbcTemplate.update("UPDATE user_account SET digest_day = ? WHERE id = ?", day, userId);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Line line(ResultSet row) throws SQLException {
        Array countries = row.getArray("work_countries");
        Instant posted = instant(row, "posted_at");
        return new Line(row.getString("employer"), row.getString("title"),
                countries == null ? List.of() : Arrays.asList((String[]) countries.getArray()),
                posted != null ? posted : instant(row, "first_seen_at"), posted != null);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * Пользователю пора получить сводку.
     *
     * @param userId пользователь
     * @param day    его сегодняшний день
     */
    public record Due(long userId, LocalDate day) {
    }

    /**
     * Получатель сводки.
     *
     * @param email     адрес
     * @param language  язык письма: {@code en} или {@code ru}
     * @param frequency частота сводки сейчас: {@code NEVER} — письмо не отправляется, даже если задание уже стоит
     *                  (аудит §165)
     * @param day       день, за который сводка уже отработана; {@code null} — ни разу
     */
    public record Recipient(String email, String language, String frequency, LocalDate day) {
    }

    /**
     * Новые вакансии.
     *
     * @param count сколько всего
     * @param lines первые строки письма
     */
    public record Fresh(long count, List<Line> lines) {
    }

    /**
     * Строка письма.
     *
     * @param employer   работодатель; {@code null} — не указан
     * @param position   позиция (название вакансии)
     * @param countries  страны; пусто — не определены
     * @param opened     когда открыта: дата работодателя или «найдена»
     * @param byEmployer дата от работодателя (иначе — дата обнаружения)
     */
    public record Line(String employer, String position, List<String> countries, Instant opened,
            boolean byEmployer) {
    }

    /** Строка письма и число всех отобранных. */
    private record Counted(long total, Line line) {
    }
}
