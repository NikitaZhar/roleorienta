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
 * Данные сводки на почту (JDBC; стенограмма §164): кому пора, новые вакансии пользователя, отметка отправленного.
 */
@Repository
public class DigestRepository {

    /**
     * Местное время пользователя в момент {@code ?} (первый параметр каждого использования): {@code AT TIME ZONE} даёт
     * время без пояса по часам пользователя; день недели ISO — понедельник 1, воскресенье 7.
     */
    private static final String LOCAL_NOW = "(CAST(? AS timestamptz) AT TIME ZONE u.time_zone)";

    /**
     * Новые вакансии пользователя: выданы по его активным условиям после прошлой сводки и сейчас в результатах — не
     * закрыты и не в «Моих откликах» или «Не интересно» (§156). Параметры — пользователь и граница прошлой сводки
     * ({@code NULL} — сводок не было).
     */
    private static final String NEW_FROM = """
             FROM search_condition c JOIN delivered_vacancy d ON d.search_condition_id = c.id
            JOIN vacancy v ON v.id = d.vacancy_id
            LEFT JOIN vacancy_user_status st ON st.user_id = c.user_id AND st.vacancy_id = v.id
            WHERE c.user_id = ? AND c.active AND d.delivered_at > coalesce(CAST(? AS timestamptz), '-infinity')
              AND v.state <> 'CLOSED' AND (st.status IS NULL OR st.status = 'VIEWED')""";

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
                SELECT email, language, digest_sent_until, digest_day FROM user_account WHERE id = ? FOR UPDATE
                """, (row, number) -> new Recipient(row.getString("email"), row.getString("language"),
                instant(row, "digest_sent_until"), row.getObject("digest_day", LocalDate.class)), userId)
                .stream().findFirst();
    }

    /**
     * @param userId    пользователь
     * @param sentUntil граница прошлой сводки; {@code null} — сводок не было
     * @param limit     строк не больше
     * @return сколько новых вакансий, граница выдачи по ним и первые строки — открытые позже сверху
     */
    public Fresh fresh(long userId, Instant sentUntil, int limit) {
        OffsetDateTime after = sentUntil == null ? null : sentUntil.atOffset(ZoneOffset.UTC);
        Totals totals = jdbcTemplate.queryForObject("SELECT count(*), max(d.delivered_at)" + NEW_FROM,
                (row, number) -> new Totals(row.getLong(1), instant(row, "max")), userId, after);
        List<Line> lines = jdbcTemplate.query("SELECT v.title, v.work_countries, v.posted_at, v.first_seen_at, "
                + EMPLOYER + " AS employer" + NEW_FROM
                + " ORDER BY coalesce(v.posted_at, v.first_seen_at) DESC, v.id DESC LIMIT ?",
                (row, number) -> line(row), userId, after, limit);
        return new Fresh(totals.count(), totals.until(), lines);
    }

    /**
     * Сводка за день отработана; граница сдвигается, только если вакансии были в письме.
     *
     * @param userId    пользователь
     * @param day       день по часам пользователя
     * @param sentUntil новая граница; {@code null} — прежняя
     */
    public void markDone(long userId, LocalDate day, Instant sentUntil) {
        jdbcTemplate.update("UPDATE user_account SET digest_day = ?, "
                + "digest_sent_until = coalesce(CAST(? AS timestamptz), digest_sent_until) WHERE id = ?", day,
                sentUntil == null ? null : sentUntil.atOffset(ZoneOffset.UTC), userId);
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
     * @param sentUntil граница прошлой сводки; {@code null} — сводок не было
     * @param day       день, за который сводка уже отработана; {@code null} — ни разу
     */
    public record Recipient(String email, String language, Instant sentUntil, LocalDate day) {
    }

    /**
     * Новые вакансии.
     *
     * @param count сколько всего
     * @param until граница выдачи по ним — момент выдачи последней
     * @param lines первые строки письма
     */
    public record Fresh(long count, Instant until, List<Line> lines) {
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

    /** Число и граница новых вакансий. */
    private record Totals(long count, Instant until) {
    }
}
