package com.roleorienta.api.vacancy;

import com.roleorienta.api.vacancy.VacancyDtos.Parties;
import com.roleorienta.api.vacancy.VacancyDtos.Publication;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Накопленный список, сведения о вакансии и отметки «не подходит» (JDBC; таблицы пишет выдача job-worker).
 */
@Repository
public class VacancyRepository {

    /**
     * Поля вакансии; работодатель и агентство — компании источников её публикаций с ролью; отметка — пользователя
     * из первого параметра запроса.
     */
    private static final String COLUMNS = """
            v.id, v.title, v.primary_url, v.first_seen_at, v.last_confirmed_at, v.state, v.work_countries,
            v.country_uncertain, v.work_format,
            (SELECT co.name FROM job_posting p JOIN company_source cs ON cs.source_id = p.source_id AND cs.role = 'EMPLOYER'
             JOIN company co ON co.id = cs.company_id WHERE p.vacancy_id = v.id ORDER BY co.id LIMIT 1) AS employer,
            (SELECT co.name FROM job_posting p JOIN company_source cs ON cs.source_id = p.source_id AND cs.role = 'AGENCY'
             JOIN company co ON co.id = cs.company_id WHERE p.vacancy_id = v.id ORDER BY co.id LIMIT 1) AS agency,
            EXISTS (SELECT 1 FROM unsuitable_mark um WHERE um.user_id = ? AND um.vacancy_id = v.id) AS unsuitable""";

    /**
     * Вакансия видна в накопленном списке версии условий (те же правила, что у выдачи job-worker): не закрыта,
     * подтверждена не раньше срока, подходит по позиции, стране и формату текущих условий, не отмечена.
     */
    private static final String VISIBLE = """
            v.state <> 'CLOSED' AND v.last_confirmed_at >= ?
            AND EXISTS (SELECT 1 FROM vacancy_position_match m WHERE m.vacancy_id = v.id AND m.position_id = c.position_id)
            AND ('*' = ANY (v.work_countries) OR v.work_countries && c.countries
                 OR coalesce(v.country_uncertain, TRUE) OR coalesce(cardinality(v.work_countries), 0) = 0)
            AND (c.work_format IS NULL OR v.work_format IS NULL OR v.work_format IN ('CONFLICT', c.work_format))
            AND NOT EXISTS (SELECT 1 FROM unsuitable_mark u WHERE u.user_id = c.user_id AND u.vacancy_id = v.id)""";

    /** Вакансия выдавалась пользователю (по любой версии условий) или отмечена им. */
    private static final String ACCESSIBLE = """
            (EXISTS (SELECT 1 FROM delivered_vacancy d JOIN search_condition c ON c.id = d.search_condition_id
                     WHERE d.vacancy_id = v.id AND c.user_id = ?)
             OR EXISTS (SELECT 1 FROM unsuitable_mark u WHERE u.vacancy_id = v.id AND u.user_id = ?))""";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public VacancyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param userId пользователь
     * @return активная версия условий; пусто — условия не заданы
     */
    public Optional<ActiveCondition> activeCondition(long userId) {
        return jdbcTemplate.query("SELECT id, work_format FROM search_condition WHERE user_id = ? AND active",
                (row, number) -> new ActiveCondition(row.getLong(1), row.getString(2)), userId).stream().findFirst();
    }

    /**
     * Видимая часть накопленного списка версии условий, новые сверху.
     *
     * @param condition    версия условий
     * @param userId       её пользователь
     * @param visibleSince не подтверждённые с этого момента не показываются
     * @param after        курсор: строки строго после него; {@code null} — с начала
     * @param limit        не больше
     * @return строки с моментом выдачи
     */
    public List<ListedRow> delivered(long condition, long userId, Instant visibleSince, Cursor after, int limit) {
        List<Object> arguments = new ArrayList<>(List.of(userId, condition, utc(visibleSince)));
        String page = page(after, "d.delivered_at", "d.vacancy_id", arguments);
        arguments.add(limit);
        return jdbcTemplate.query("SELECT " + COLUMNS + ", d.delivered_at AS listed_at FROM delivered_vacancy d "
                + "JOIN search_condition c ON c.id = d.search_condition_id JOIN vacancy v ON v.id = d.vacancy_id "
                + "WHERE d.search_condition_id = ? AND " + VISIBLE + page
                + " ORDER BY d.delivered_at DESC, d.vacancy_id DESC LIMIT ?", VacancyRepository::listed,
                arguments.toArray());
    }

    /**
     * Отмеченные пользователем вакансии, последние отмеченные сверху.
     *
     * @param userId пользователь
     * @param after  курсор; {@code null} — с начала
     * @param limit  не больше
     * @return строки с моментом отметки
     */
    public List<ListedRow> marked(long userId, Cursor after, int limit) {
        List<Object> arguments = new ArrayList<>(List.of(userId, userId));
        String page = page(after, "u.marked_at", "u.vacancy_id", arguments);
        arguments.add(limit);
        return jdbcTemplate.query("SELECT " + COLUMNS + ", u.marked_at AS listed_at FROM unsuitable_mark u "
                + "JOIN vacancy v ON v.id = u.vacancy_id WHERE u.user_id = ?" + page
                + " ORDER BY u.marked_at DESC, u.vacancy_id DESC LIMIT ?", VacancyRepository::listed,
                arguments.toArray());
    }

    /**
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @return вакансия, если она выдавалась пользователю или отмечена им; иначе пусто
     */
    public Optional<VacancyRow> accessible(long userId, long vacancyId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM vacancy v WHERE v.id = ? AND " + ACCESSIBLE,
                (row, number) -> vacancy(row), userId, vacancyId, userId, userId).stream().findFirst();
    }

    /**
     * Отметка «не подходит»; повтор ничего не меняет.
     *
     * @param userId    пользователь
     * @param vacancyId вакансия
     */
    public void mark(long userId, long vacancyId) {
        jdbcTemplate.update("INSERT INTO unsuitable_mark (user_id, vacancy_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, vacancyId);
    }

    /**
     * Снятие отметки; снятой нет — ничего.
     *
     * @param userId    пользователь
     * @param vacancyId вакансия
     */
    public void unmark(long userId, long vacancyId) {
        jdbcTemplate.update("DELETE FROM unsuitable_mark WHERE user_id = ? AND vacancy_id = ?", userId, vacancyId);
    }

    private static String page(Cursor after, String timeColumn, String idColumn, List<Object> arguments) {
        if (after == null) {
            return "";
        }
        arguments.add(utc(after.at()));
        arguments.add(after.id());
        return " AND (" + timeColumn + ", " + idColumn + ") < (?, ?)";
    }

    private static ListedRow listed(ResultSet row, int number) throws SQLException {
        return new ListedRow(vacancy(row), row.getObject("listed_at", OffsetDateTime.class).toInstant());
    }

    private static VacancyRow vacancy(ResultSet row) throws SQLException {
        Array countries = row.getArray("work_countries");
        Boolean uncertain = (Boolean) row.getObject("country_uncertain");
        return new VacancyRow(row.getLong("id"), row.getString("title"),
                new Parties(row.getString("employer"), row.getString("agency")),
                new WorkFacts(countries == null ? List.of() : List.of((String[]) countries.getArray()),
                        row.getString("work_format"), uncertain == null || uncertain),
                new Publication(row.getString("primary_url"),
                        row.getObject("first_seen_at", OffsetDateTime.class).toInstant(),
                        row.getObject("last_confirmed_at", OffsetDateTime.class).toInstant(), row.getString("state"),
                        row.getBoolean("unsuitable")));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Активная версия условий.
     *
     * @param id         версия
     * @param workFormat формат в условиях; {@code null} — не задан
     */
    public record ActiveCondition(long id, String workFormat) {
    }

    /**
     * Вакансия из БД.
     *
     * @param id          вакансия
     * @param title       позиция
     * @param parties     работодатель и агентство
     * @param work        страны, формат, неясность страны — как записаны
     * @param publication ссылка, даты, состояние, отметка
     */
    public record VacancyRow(long id, String title, Parties parties, WorkFacts work, Publication publication) {
    }

    /**
     * Сведения о работе, как их записал предрасчёт (job-worker).
     *
     * @param countries        страны; пусто — не определены
     * @param format           {@code OFFICE}/{@code HYBRID}/{@code REMOTE}/{@code CONFLICT}; {@code null} — не указан
     * @param countryUncertain есть место без ясной страны (или признак не рассчитан)
     */
    public record WorkFacts(List<String> countries, String format, boolean countryUncertain) {
    }

    /**
     * Строка списка.
     *
     * @param vacancy  вакансия
     * @param listedAt момент выдачи (или отметки)
     */
    public record ListedRow(VacancyRow vacancy, Instant listedAt) {
    }

    /**
     * Позиция в списке: строки строго после неё (время, затем id — по убыванию).
     *
     * @param at время строки
     * @param id вакансия
     */
    public record Cursor(Instant at, long id) {
    }
}
