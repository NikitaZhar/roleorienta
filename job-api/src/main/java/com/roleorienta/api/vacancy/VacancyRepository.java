package com.roleorienta.api.vacancy;

import com.roleorienta.api.vacancy.VacancyDtos.LanguageRequirement;
import com.roleorienta.api.vacancy.VacancyDtos.Opened;
import com.roleorienta.api.vacancy.VacancyDtos.Parties;
import com.roleorienta.api.vacancy.VacancyDtos.PositionCount;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * {@code st}, строки может не быть — «Новая»); ссылка — вакансия у работодателя, если объявление profesia.sk её
     * дало (§209, договорённость §178), иначе первичная публикация; «Получена» — первая выдача пользователю по любой версии условий;
     * «Закрыта работодателем» — время закрытия у закрытой вакансии (без него — последнее подтверждение).
     */
    private static final String COLUMNS = """
            v.id, v.title,
            coalesce((SELECT p.employer_url FROM job_posting p
                      WHERE p.vacancy_id = v.id AND p.employer_url IS NOT NULL ORDER BY p.id LIMIT 1),
                     v.primary_url) AS primary_url,
            v.first_seen_at, v.posted_at, v.work_countries, v.work_format,
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
     * Вакансия видна в результатах версии условий (те же правила, что у выдачи и сводки на почту job-worker —
     * {@code DeliveryRepository.PORTION}, {@code DigestRepository.FRESH_FROM}; меняются вместе): не закрыта,
     * подтверждена не раньше срока, подходит хотя бы к одной позиции или группе условий (§126, §204: представление
     * {@code search_condition_match}, V77), стране (среди определённых стран
     * вакансии есть страна условий или страна не определена — стран нет; только «*», удалённо «весь мир», и не
     * рассчитанные страны — нет; решения владельца 2026-10-20, §148) и формату текущих условий (гибрид — при любом
     * формате, §125); статус пользователя — «Новая» или «Просмотрена» (остальные — в «Моих откликах» и «Не
     * интересно», §156).
     */
    private static final String VISIBLE = """
            v.state <> 'CLOSED' AND v.last_confirmed_at >= ?
            AND EXISTS (SELECT 1 FROM search_condition_match sm
                        WHERE sm.search_condition_id = c.id AND sm.vacancy_id = v.id)
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

    /**
     * Отбор по одной позиции или группе условий внутри результатов (§194, §204): параметр — код дважды; {@code null} —
     * все.
     */
    private static final String ONE_POSITION = """
             AND (CAST(? AS TEXT) IS NULL OR EXISTS (SELECT 1 FROM search_condition_match fm
                                                    WHERE fm.search_condition_id = c.id AND fm.vacancy_id = v.id
                                                      AND fm.code = ?))""";

    /**
     * Позиции и группы версии условий с названиями (у группы — на языке интерфейса, иначе английское; §204): код и
     * название; параметры — версия условий, язык, версия условий.
     */
    private static final String ITEMS = """
            (SELECT p.code, p.name FROM search_condition_position s JOIN position p ON p.id = s.position_id
             WHERE s.search_condition_id = ?
             UNION ALL
             SELECT o.code, coalesce(
                    (SELECT n.name FROM occupation_group_name n WHERE n.code = o.code AND n.language = ?),
                    (SELECT n.name FROM occupation_group_name n WHERE n.code = o.code AND n.language = 'en'))
             FROM search_condition_occupation o WHERE o.search_condition_id = ?)""";

    /**
     * Отбор «после прошлого захода» внутри результатов (§200): вакансия впервые получена пользователем (первая выдача по
     * любой версии его условий) позже границы захода; параметр — граница дважды; {@code null} — без отбора.
     */
    private static final String SINCE_VISIT = """
             AND (CAST(? AS TIMESTAMPTZ) IS NULL
                  OR (SELECT min(dd.delivered_at) FROM delivered_vacancy dd
                      JOIN search_condition cc ON cc.id = dd.search_condition_id
                      WHERE dd.vacancy_id = v.id AND cc.user_id = c.user_id) > ?)""";

    /** Вакансии пользователя с текущим статусом из списка ({@code %1$s}); параметр — пользователь. */
    private static final String STATUS_FROM = """
             FROM vacancy_user_status st JOIN vacancy v ON v.id = st.vacancy_id
            WHERE st.user_id = ? AND st.status IN (%1$s)""";

    /**
     * Отклики пользователя (§196, §213): параметры — пользователь и «завершённые». Действующие — «Applied» и
     * «Interview» по вакансиям, которые работодатель не закрыл; завершённые — «Employer declined» и отклики по закрытым
     * работодателем: ответа по ним уже не будет, сверху они только мешают видеть, с кем идёт работа.
     */
    private static final String RESPONSES_FROM = """
             FROM vacancy_user_status st JOIN vacancy v ON v.id = st.vacancy_id
            WHERE st.user_id = ?
              AND CASE WHEN CAST(? AS BOOLEAN)
                       THEN st.status = 'REJECTED' OR (st.status IN ('APPLIED', 'INTERVIEW') AND v.state = 'CLOSED')
                       ELSE st.status IN ('APPLIED', 'INTERVIEW') AND v.state <> 'CLOSED' END""";

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
    public Optional<Long> activeCondition(long userId) {
        return jdbcTemplate.query("SELECT id FROM search_condition WHERE user_id = ? AND active",
                (row, number) -> row.getLong(1), userId).stream().findFirst();
    }

    /**
     * Открытие результатов — заход в список (§191): время открытия запоминается ({@code list_seen_at}); если с прошлого
     * открытия прошло больше {@code gap}, начинается новый заход и его граница ({@code list_visit_from}) — прошлое
     * открытие, то есть конец прошлого захода. Внутри захода граница не меняется. Одно {@code UPDATE}: выражения
     * {@code SET} читают значения строки до изменения.
     *
     * @param userId пользователь
     * @param gap    перерыв, после которого открытие — новый заход
     * @return граница текущего захода: вакансии, впервые полученные позже, — «после прошлого захода»; {@code null} —
     *         прошлого захода не было (первый заход)
     */
    public Instant recordVisit(long userId, Duration gap) {
        return jdbcTemplate.query("""
                UPDATE user_account SET list_visit_from = CASE
                           WHEN list_seen_at < now() - make_interval(secs => ?) THEN list_seen_at
                           ELSE list_visit_from END,
                       list_seen_at = now()
                WHERE id = ? RETURNING list_visit_from
                """, (row, number) -> instant(row, "list_visit_from"), (double) gap.toSeconds(), userId)
                .stream().filter(Objects::nonNull).findFirst().orElse(null);
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
     * @param position     код позиции или группы условий — только подходящие к ней (§194, §204); {@code null} — все
     * @param recentFrom   только впервые полученные позже (§200: «после прошлого захода»); {@code null} — все
     * @return вакансии страницы
     */
    public List<VacancyRow> delivered(long condition, Instant visibleSince, PageWindow page, String position,
            Instant recentFrom) {
        OffsetDateTime recent = recentFrom == null ? null : utc(recentFrom);
        return jdbcTemplate.query("SELECT " + COLUMNS.formatted("c.user_id") + DELIVERED_FROM + ONE_POSITION
                + SINCE_VISIT + " ORDER BY " + OPENED + " DESC, d.vacancy_id DESC LIMIT ? OFFSET ?",
                (row, number) -> vacancy(row), condition, utc(visibleSince), position, position, recent, recent,
                page.size(), page.offset());
    }

    /**
     * @param condition    версия условий
     * @param visibleSince не подтверждённые с этого момента не показываются
     * @param position     код позиции или группы условий (§194, §204); {@code null} — все
     * @param recentFrom   только впервые полученные позже (§200); {@code null} — все
     * @return сколько вакансий в видимой части накопленного списка (те же условия, что у {@link #delivered}) и
     *         сколько из них новых (статуса пользователя нет)
     */
    public Counts countDelivered(long condition, Instant visibleSince, String position, Instant recentFrom) {
        OffsetDateTime recent = recentFrom == null ? null : utc(recentFrom);
        return jdbcTemplate.queryForObject("SELECT count(*), count(*) FILTER (WHERE st.status IS NULL)"
                + DELIVERED_FROM + ONE_POSITION + SINCE_VISIT,
                (row, number) -> new Counts(row.getLong(1), row.getLong(2)),
                condition, utc(visibleSince), position, position, recent, recent);
    }

    /**
     * Позиции и группы условий с числом видимых вакансий каждой (§194: метки отбора над результатами; §204); одна
     * вакансия может подходить к нескольким. Видимость — как у {@link #delivered}.
     *
     * @param condition    версия условий
     * @param visibleSince не подтверждённые с этого момента не считаются
     * @param language     язык названий групп
     * @return позиции и группы условий, больше вакансий — первыми, при равном числе — по названию
     */
    public List<PositionCount> positionCounts(long condition, Instant visibleSince, String language) {
        return jdbcTemplate.query("""
                SELECT i.code, i.name, (SELECT count(*)""" + DELIVERED_FROM + """
                     AND EXISTS (SELECT 1 FROM search_condition_match pm
                                 WHERE pm.search_condition_id = c.id AND pm.vacancy_id = v.id
                                   AND pm.code = i.code)) AS total
                FROM """ + ITEMS + """
                 i (code, name)
                ORDER BY total DESC, i.name
                """, (row, number) -> new PositionCount(row.getString(1), row.getString(2), row.getLong(3)),
                condition, utc(visibleSince), condition, language, condition);
    }

    /**
     * @param condition версия условий
     * @param vacancies вакансии страницы
     * @param language  язык названий групп
     * @return к каким позициям и группам условий подошла каждая вакансия (§194, §204): вакансия → названия по алфавиту
     */
    public Map<Long, List<String>> matchedPositions(long condition, List<Long> vacancies, String language) {
        Map<Long, List<String>> matched = new HashMap<>();
        jdbcTemplate.query("""
                SELECT DISTINCT m.vacancy_id, i.name FROM search_condition_match m
                JOIN """ + ITEMS + """
                 i (code, name) ON i.code = m.code
                WHERE m.search_condition_id = ? AND m.vacancy_id = ANY(?) ORDER BY i.name
                """, row -> {
                    matched.computeIfAbsent(row.getLong(1), any -> new ArrayList<>()).add(row.getString(2));
                }, condition, language, condition, condition, vacancies.toArray(Long[]::new));
        return matched;
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
     * Страница откликов, статус изменён позже сверху.
     *
     * @param userId   пользователь
     * @param finished {@code true} — завершённые; {@code false} — действующие ({@link #RESPONSES_FROM})
     * @param page     смещение и размер страницы
     * @return вакансии страницы
     */
    public List<VacancyRow> responses(long userId, boolean finished, PageWindow page) {
        return jdbcTemplate.query("SELECT " + COLUMNS.formatted("st.user_id") + RESPONSES_FROM
                + " ORDER BY st.changed_at DESC, st.vacancy_id DESC LIMIT ? OFFSET ?",
                (row, number) -> vacancy(row), userId, finished, page.size(), page.offset());
    }

    /**
     * @param userId   пользователь
     * @param finished {@code true} — завершённые; {@code false} — действующие
     * @return сколько откликов в разделе
     */
    public long countResponses(long userId, boolean finished) {
        return jdbcTemplate.queryForObject("SELECT count(*)" + RESPONSES_FROM, Long.class, userId, finished);
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
     * @param vacancyId вакансия
     * @return требования к языку (предрасчёт job-worker, §158), по коду языка; пусто — не указаны
     */
    public List<LanguageRequirement> languages(long vacancyId) {
        return jdbcTemplate.query("SELECT language, required FROM vacancy_language WHERE vacancy_id = ? ORDER BY 1",
                (row, number) -> new LanguageRequirement(row.getString(1), row.getBoolean(2)), vacancyId);
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
