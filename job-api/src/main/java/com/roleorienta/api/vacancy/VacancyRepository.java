package com.roleorienta.api.vacancy;

import com.roleorienta.api.vacancy.VacancyDtos.Opened;
import com.roleorienta.api.vacancy.VacancyDtos.Parties;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Накопленный список, сведения о вакансии и статусы вакансии у пользователя (JDBC; накопленный список пишет выдача
 * job-worker, статусы — этот сервис; стенограмма §156).
 */
@Repository
public class VacancyRepository {

    /**
     * Поля вакансии; {@code %1$s} — колонка с id пользователя в запросе. Работодатель и агентство — компании источников
     * её публикаций с ролью (работодатель — и компания с кадровым сайтом группы, роль {@code GROUP}, §81, — после
     * компании с ролью {@code EMPLOYER}, аудит §84; нет компании-работодателя — название работодателя у системы найма
     * для источника без компании, аудит §65). Статус пользователя — из {@code vacancy_user_status} (псевдоним
     * {@code st}, строки может не быть — «Новая»); «Получена» — первая выдача пользователю по любой версии условий;
     * «Закрыта работодателем» — время закрытия у закрытой вакансии (без него — последнее подтверждение).
     */
    private static final String COLUMNS = """
            v.id, v.title, v.primary_url, v.first_seen_at, v.posted_at, v.work_countries, v.work_format,
            CASE WHEN v.state = 'CLOSED' THEN coalesce(v.closed_at, v.last_confirmed_at) END AS closed_at,
            st.status AS user_status, st.changed_at,
            (SELECT min(dd.delivered_at) FROM delivered_vacancy dd
             JOIN search_condition cc ON cc.id = dd.search_condition_id
             WHERE dd.vacancy_id = v.id AND cc.user_id = %1$s) AS received_at,
            coalesce((SELECT co.name FROM job_posting p
                      JOIN company_source cs ON cs.source_id = p.source_id AND cs.role IN ('EMPLOYER', 'GROUP')
                      JOIN company co ON co.id = cs.company_id WHERE p.vacancy_id = v.id
                      ORDER BY cs.role = 'GROUP', co.id LIMIT 1),
                     (SELECT s.employer_name FROM job_posting p JOIN source s ON s.id = p.source_id
                      WHERE p.vacancy_id = v.id AND s.employer_name IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM company_source cs WHERE cs.source_id = s.id)
                      ORDER BY s.id LIMIT 1)) AS employer,
            (SELECT co.name FROM job_posting p JOIN company_source cs ON cs.source_id = p.source_id AND cs.role = 'AGENCY'
             JOIN company co ON co.id = cs.company_id WHERE p.vacancy_id = v.id ORDER BY co.id LIMIT 1) AS agency""";

    /** Дата открытия вакансии (§127): размещена работодателем, иначе — впервые найдена. */
    private static final String OPENED = "coalesce(v.posted_at, v.first_seen_at)";

    /**
     * Вакансия видна в результатах версии условий (те же правила, что у выдачи job-worker): не закрыта,
     * подтверждена не раньше срока, подходит хотя бы к одной позиции условий (§126), стране (среди определённых стран
     * вакансии есть страна условий или страна не определена — стран нет; только «*», удалённо «весь мир», и не
     * рассчитанные страны — нет; решения владельца 2026-10-20, §148) и формату текущих условий (гибрид — при любом
     * формате, §125); статус пользователя — «Новая» или «Просмотрена» (остальные — в «Моих откликах» и «Не
     * интересно», §156).
     */
    private static final String VISIBLE = """
            v.state <> 'CLOSED' AND v.last_confirmed_at >= ?
            AND EXISTS (SELECT 1 FROM vacancy_position_match m
                        JOIN search_condition_position sp ON sp.position_id = m.position_id
                        WHERE m.vacancy_id = v.id AND sp.search_condition_id = c.id)
            AND (v.work_countries && c.countries OR cardinality(v.work_countries) = 0)
            AND (c.work_format IS NULL OR v.work_format IS NULL
                 OR v.work_format IN ('CONFLICT', 'HYBRID', c.work_format))
            AND (st.status IS NULL OR st.status = 'VIEWED')""";

    /** Видимая часть накопленного списка версии условий: параметры — версия условий и срок подтверждения. */
    private static final String DELIVERED_FROM = """
             FROM delivered_vacancy d JOIN search_condition c ON c.id = d.search_condition_id
            JOIN vacancy v ON v.id = d.vacancy_id
            LEFT JOIN vacancy_user_status st ON st.user_id = c.user_id AND st.vacancy_id = v.id
            WHERE d.search_condition_id = ?""" + " AND " + VISIBLE;

    /** Вакансии пользователя с текущим статусом из списка ({@code %1$s}); параметр — пользователь. */
    private static final String STATUS_FROM = """
             FROM vacancy_user_status st JOIN vacancy v ON v.id = st.vacancy_id
            WHERE st.user_id = ? AND st.status IN (%1$s)""";

    /**
     * Вакансия по id для пользователя ({@code me.user_id}): видна, если выдавалась ему (по любой версии условий) или у
     * неё есть его статус; параметры — пользователь и вакансия.
     */
    private static final String ACCESSIBLE_FROM = """
             FROM (SELECT CAST(? AS BIGINT) AS user_id) me JOIN vacancy v ON v.id = ?
            LEFT JOIN vacancy_user_status st ON st.user_id = me.user_id AND st.vacancy_id = v.id
            WHERE st.status IS NOT NULL
               OR EXISTS (SELECT 1 FROM delivered_vacancy d JOIN search_condition c ON c.id = d.search_condition_id
                          WHERE d.vacancy_id = v.id AND c.user_id = me.user_id)""";

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
     * Страница видимой части накопленного списка версии условий: открытые позже сверху — по дате размещения
     * работодателем, а без неё по дате первого обнаружения ({@code coalesce(posted_at, first_seen_at)}; решение
     * владельца 2026-10-16, §127); при равной дате — больший id выше, порядок однозначен. Страницы по номеру
     * (решение владельца 2026-10-20, §151): {@code LIMIT … OFFSET …}.
     *
     * @param condition    версия условий
     * @param visibleSince не подтверждённые с этого момента не показываются
     * @param page         смещение и размер страницы
     * @return вакансии страницы
     */
    public List<VacancyRow> delivered(long condition, Instant visibleSince, PageWindow page) {
        return jdbcTemplate.query("SELECT " + COLUMNS.formatted("c.user_id") + DELIVERED_FROM
                + " ORDER BY " + OPENED + " DESC, d.vacancy_id DESC LIMIT ? OFFSET ?",
                (row, number) -> vacancy(row), condition, utc(visibleSince), page.size(), page.offset());
    }

    /**
     * @param condition    версия условий
     * @param visibleSince не подтверждённые с этого момента не показываются
     * @return сколько вакансий в видимой части накопленного списка (те же условия, что у {@link #delivered}) и
     *         сколько из них новых (статуса пользователя нет)
     */
    public Counts countDelivered(long condition, Instant visibleSince) {
        return jdbcTemplate.queryForObject("SELECT count(*), count(*) FILTER (WHERE st.status IS NULL)"
                + DELIVERED_FROM, (row, number) -> new Counts(row.getLong(1), row.getLong(2)), condition,
                utc(visibleSince));
    }

    /**
     * Страница вакансий пользователя с текущим статусом из списка, статус изменён позже сверху; закрытые
     * работодателем остаются (бизнес-описание §6).
     *
     * @param userId   пользователь
     * @param statuses статусы раздела
     * @param page     смещение и размер страницы
     * @return вакансии страницы
     */
    public List<VacancyRow> byStatus(long userId, List<StatusEvent> statuses, PageWindow page) {
        return jdbcTemplate.query("SELECT " + COLUMNS.formatted("st.user_id") + STATUS_FROM.formatted(names(statuses))
                + " ORDER BY st.changed_at DESC, st.vacancy_id DESC LIMIT ? OFFSET ?",
                (row, number) -> vacancy(row), userId, page.size(), page.offset());
    }

    /**
     * @param userId   пользователь
     * @param statuses статусы раздела
     * @return сколько вакансий пользователя с таким текущим статусом
     */
    public long countByStatus(long userId, List<StatusEvent> statuses) {
        return jdbcTemplate.queryForObject("SELECT count(*)" + STATUS_FROM.formatted(names(statuses)), Long.class,
                userId);
    }

    /**
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @return вакансия, если она выдавалась пользователю или у неё есть его статус; иначе пусто
     */
    public Optional<VacancyRow> accessible(long userId, long vacancyId) {
        return jdbcTemplate.query("SELECT " + COLUMNS.formatted("me.user_id") + ACCESSIBLE_FROM,
                (row, number) -> vacancy(row), userId, vacancyId).stream().findFirst();
    }

    /**
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @return {@code true} — вакансия выдавалась пользователю или у неё есть его статус
     */
    public boolean isAccessible(long userId, long vacancyId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1" + ACCESSIBLE_FROM + ")",
                Boolean.class, userId, vacancyId));
    }

    /**
     * Блокирует строку пользователя до конца транзакции: смены статусов одного пользователя идут по одной, и
     * чтение «текущий статус и история → запись» не гоняется с параллельным запросом (контракт §3.5). Одна
     * блокировка на запрос — взаимной блокировки нет.
     *
     * @param userId пользователь
     */
    public void lockUser(long userId) {
        jdbcTemplate.queryForList("SELECT id FROM user_account WHERE id = ? FOR UPDATE", Long.class, userId);
    }

    /**
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @return текущий статус; пусто — вакансия новая
     */
    public Optional<StatusEvent> currentStatus(long userId, long vacancyId) {
        return jdbcTemplate.queryForList("SELECT status FROM vacancy_user_status WHERE user_id = ? AND vacancy_id = ?",
                String.class, userId, vacancyId).stream().map(StatusEvent::valueOf).findFirst();
    }

    /**
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @return строки истории по времени
     */
    public List<StoredLine> history(long userId, long vacancyId) {
        return jdbcTemplate.query("""
                SELECT id, status, set_at, local_day FROM vacancy_status_history
                WHERE user_id = ? AND vacancy_id = ? ORDER BY set_at, id
                """, (row, number) -> new StoredLine(row.getLong("id"), StatusEvent.valueOf(row.getString("status")),
                row.getObject("set_at", OffsetDateTime.class).toInstant(),
                row.getObject("local_day", LocalDate.class)), userId, vacancyId);
    }

    /**
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @param event     статус строки
     * @param at        когда поставлен
     * @param day       день пользователя
     */
    public void appendLine(long userId, long vacancyId, StatusEvent event, Instant at, LocalDate day) {
        jdbcTemplate.update("""
                INSERT INTO vacancy_status_history (user_id, vacancy_id, status, set_at, local_day)
                VALUES (?, ?, ?, ?, ?)
                """, userId, vacancyId, event.name(), utc(at), day);
    }

    /**
     * Строка истории заменяется новым статусом того же дня (исправление ошибочного нажатия).
     *
     * @param lineId строка истории
     * @param event  новый статус
     * @param at     когда поставлен
     */
    public void replaceLine(long lineId, StatusEvent event, Instant at) {
        jdbcTemplate.update("UPDATE vacancy_status_history SET status = ?, set_at = ? WHERE id = ?", event.name(),
                utc(at), lineId);
    }

    /**
     * @param userId    пользователь
     * @param vacancyId вакансия
     * @param status    новый текущий статус
     * @param at        когда изменён
     */
    public void saveCurrent(long userId, long vacancyId, StatusEvent status, Instant at) {
        jdbcTemplate.update("""
                INSERT INTO vacancy_user_status (user_id, vacancy_id, status, changed_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, vacancy_id) DO UPDATE SET status = EXCLUDED.status, changed_at = EXCLUDED.changed_at
                """, userId, vacancyId, status.name(), utc(at));
    }

    /** Статусы как SQL-список; значения — имена перечисления, не данные пользователя. */
    private static String names(List<StatusEvent> statuses) {
        return statuses.stream().map(status -> "'" + status.name() + "'").collect(Collectors.joining(", "));
    }

    private static VacancyRow vacancy(ResultSet row) throws SQLException {
        Array countries = row.getArray("work_countries");
        String status = row.getString("user_status");
        CurrentStatus current = status == null ? null : new CurrentStatus(StatusEvent.valueOf(status),
                row.getObject("changed_at", OffsetDateTime.class).toInstant());
        return new VacancyRow(row.getLong("id"), row.getString("title"),
                new Parties(row.getString("employer"), row.getString("agency")),
                new WorkFacts(countries == null ? List.of() : List.of((String[]) countries.getArray()),
                        row.getString("work_format")),
                new RowState(row.getString("primary_url"), opened(row), instant(row, "received_at"),
                        instant(row, "closed_at"), current));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Opened opened(ResultSet row) throws SQLException {
        OffsetDateTime posted = row.getObject("posted_at", OffsetDateTime.class);
        return posted != null ? new Opened(posted.toInstant(), true)
                : new Opened(row.getObject("first_seen_at", OffsetDateTime.class).toInstant(), false);
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
     * @param id      вакансия
     * @param title   позиция
     * @param parties работодатель и агентство
     * @param work    страны и формат — как записаны
     * @param state   ссылка, даты и статус пользователя
     */
    public record VacancyRow(long id, String title, Parties parties, WorkFacts work, RowState state) {
    }

    /**
     * Ссылка, даты и статус пользователя.
     *
     * @param url        первичная публикация
     * @param opened     когда открыта (§127)
     * @param receivedAt первая выдача пользователю; {@code null} — не выдавалась
     * @param closedAt   когда закрыта работодателем; {@code null} — не закрыта
     * @param current    текущий статус; {@code null} — вакансия новая
     */
    public record RowState(String url, Opened opened, Instant receivedAt, Instant closedAt, CurrentStatus current) {
    }

    /**
     * Текущий статус пользователя.
     *
     * @param status    статус
     * @param changedAt когда изменён
     */
    public record CurrentStatus(StatusEvent status, Instant changedAt) {
    }

    /**
     * Строка истории из БД.
     *
     * @param id    строка
     * @param event статус
     * @param at    когда поставлен
     * @param day   день пользователя
     */
    public record StoredLine(long id, StatusEvent event, Instant at, LocalDate day) {
    }

    /**
     * Число вакансий в результатах.
     *
     * @param total всего
     * @param fresh новых
     */
    public record Counts(long total, long fresh) {
    }

    /**
     * Сведения о работе, как их записал предрасчёт (job-worker).
     *
     * @param countries страны; пусто — не определены
     * @param format    {@code OFFICE}/{@code HYBRID}/{@code REMOTE}/{@code CONFLICT}; {@code null} — не указан
     */
    public record WorkFacts(List<String> countries, String format) {
    }

    /**
     * Окно страницы в SQL.
     *
     * @param offset сколько строк пропустить
     * @param size   сколько строк взять
     */
    public record PageWindow(long offset, int size) {
    }
}
