package com.roleorienta.api.dictionary;

import com.roleorienta.api.dictionary.DictionaryDtos.ChosenWithoutVacancies;
import com.roleorienta.api.dictionary.DictionaryDtos.GroupHint;
import com.roleorienta.api.dictionary.DictionaryDtos.Placement;
import com.roleorienta.api.dictionary.DictionaryDtos.PositionView;
import com.roleorienta.api.dictionary.DictionaryDtos.ProfesiaPosition;
import com.roleorienta.api.dictionary.DictionaryDtos.SearchMiss;
import com.roleorienta.api.dictionary.DictionaryDtos.Settings;
import com.roleorienta.api.dictionary.DictionaryDtos.Terms;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Словарь позиций в БД: {@code position}, {@code position_term}, {@code position_dictionary} (JDBC; стенограмма §159,
 * §160). Читает его и job-worker при сопоставлении.
 */
@Repository
public class DictionaryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public DictionaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Блокирует строку настроек словаря до конца транзакции: изменения словаря идут по одному, номер версии растёт без
     * пропусков и повторов (контракт §3.5). Одна блокировка на изменение — взаимной блокировки нет.
     *
     * @return номер версии
     */
    public int lockVersion() {
        return jdbcTemplate.queryForObject("SELECT version FROM position_dictionary WHERE id = 1 FOR UPDATE",
                Integer.class);
    }

    /** Номер версии словаря +1: воркер пересчитает соответствия всех вакансий. */
    public void bumpVersion() {
        jdbcTemplate.update("UPDATE position_dictionary SET version = version + 1 WHERE id = 1");
    }

    /**
     * @return номер версии и общие настройки
     */
    public Versioned<Settings> settings() {
        return jdbcTemplate.queryForObject(
                "SELECT version, content_hits, other_specializations FROM position_dictionary WHERE id = 1",
                (row, number) -> new Versioned<>(row.getInt("version"),
                        new Settings(row.getInt("content_hits"), strings(row.getArray("other_specializations")))));
    }

    /**
     * @param settings общие настройки
     */
    public void saveSettings(Settings settings) {
        jdbcTemplate.update("UPDATE position_dictionary SET content_hits = ?, other_specializations = ? WHERE id = 1",
                settings.contentHits(), settings.otherSpecializations().toArray(String[]::new));
    }

    /**
     * @return позиции с названиями по видам, по названию; позиция без названий — с пустыми списками
     */
    public List<PositionView> positions() {
        Map<Long, Row> rows = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT p.id, p.code, p.name, p.active, p.occupation_code, t.kind, t.term
                FROM position p LEFT JOIN position_term t ON t.position_id = p.id
                ORDER BY p.name, p.id, t.id
                """, row -> {
                    long id = row.getLong("id");
                    String code = row.getString("code");
                    String name = row.getString("name");
                    boolean active = row.getBoolean("active");
                    String group = row.getString("occupation_code");
                    String kind = row.getString("kind");
                    String term = row.getString("term");
                    Row position = rows.computeIfAbsent(id, any -> new Row(code, name, group, active,
                            new EnumMap<>(TermKind.class)));
                    if (kind != null) {
                        position.terms().computeIfAbsent(TermKind.valueOf(kind), any -> new ArrayList<>()).add(term);
                    }
                });
        List<PositionView> positions = new ArrayList<>();
        rows.forEach((id, row) -> positions.add(new PositionView(id, row.code(), row.name(),
                new Placement(row.group(), row.active()),
                new Terms(row.of(TermKind.TITLE), row.of(TermKind.ROLE), row.of(TermKind.KEYWORD),
                        row.of(TermKind.EXCLUDE)))));
        return positions;
    }

    /**
     * @param code  код
     * @param name  название
     * @param group группа ISCO-08
     * @return id новой позиции; пусто — позиция с таким кодом уже есть
     */
    public Optional<Long> insertPosition(String code, String name, String group) {
        return jdbcTemplate.queryForList("""
                INSERT INTO position (code, name, occupation_code) VALUES (?, ?, ?) ON CONFLICT (code) DO NOTHING
                RETURNING id
                """, Long.class, code, name, group).stream().findFirst();
    }

    /**
     * @param id     позиция
     * @param name   название
     * @param active включена
     * @param group  группа ISCO-08
     * @return {@code false} — позиции нет
     */
    public boolean updatePosition(long id, String name, boolean active, String group) {
        return jdbcTemplate.update("UPDATE position SET name = ?, active = ?, occupation_code = ? WHERE id = ?", name,
                active, group, id) == 1;
    }

    /**
     * @return запросы подсказки без результата (§205): чаще — первыми, при равном числе — недавние; не больше 100
     */
    public List<SearchMiss> searchMisses() {
        return jdbcTemplate.query("SELECT query, misses, last_at FROM position_search_miss "
                + "ORDER BY misses DESC, last_at DESC LIMIT 100", (row, number) -> new SearchMiss(row.getString(1),
                row.getInt(2), row.getObject(3, OffsetDateTime.class).toInstant()));
    }

    /**
     * @return позиции и группы действующих условий без единой открытой вакансии (§214; соответствие — представление
     *         {@code search_condition_match}, как у выдачи): чаще выбранные — первыми
     */
    public List<ChosenWithoutVacancies> chosenWithoutVacancies() {
        return jdbcTemplate.query("""
                WITH chosen AS (
                    SELECT p.code, p.name, count(*) AS conditions
                    FROM search_condition_position s JOIN search_condition c ON c.id = s.search_condition_id AND c.active
                    JOIN position p ON p.id = s.position_id GROUP BY p.code, p.name
                    UNION ALL
                    SELECT o.code, n.name, count(*)
                    FROM search_condition_occupation o
                    JOIN search_condition c ON c.id = o.search_condition_id AND c.active
                    JOIN occupation_group_name n ON n.code = o.code AND n.language = 'en' GROUP BY o.code, n.name)
                SELECT ch.code, ch.name, ch.conditions FROM chosen ch
                WHERE NOT EXISTS (SELECT 1 FROM search_condition_match m
                                  JOIN search_condition c ON c.id = m.search_condition_id AND c.active
                                  JOIN vacancy v ON v.id = m.vacancy_id AND v.state <> 'CLOSED'
                                  WHERE m.code = ch.code)
                ORDER BY ch.conditions DESC, ch.name
                """, (row, number) -> new ChosenWithoutVacancies(row.getString(1), row.getString(2), row.getLong(3)));
    }

    /**
     * @param query запрос
     * @return {@code false} — такого нет
     */
    public boolean forgetSearchMiss(String query) {
        return jdbcTemplate.update("DELETE FROM position_search_miss WHERE query = ?", query) == 1;
    }

    /**
     * Вакансия {@code v.vacancy_id} не находится ни одним условием поиска: ни позиции словаря, ни кода портала, ни кода
     * по названию, ни позиции profesia.sk с группой у её открытых публикаций (пути представления
     * {@code search_condition_match}, V97).
     */
    private static final String UNSEEN = """
            NOT EXISTS (SELECT 1 FROM vacancy_position_match m WHERE m.vacancy_id = v.vacancy_id)
            AND NOT EXISTS (SELECT 1 FROM job_posting y WHERE y.vacancy_id = v.vacancy_id AND y.closed_at IS NULL
                            AND (y.occupation_code IS NOT NULL OR y.title_occupation_code IS NOT NULL))
            AND NOT EXISTS (SELECT 1 FROM job_posting y
                            JOIN profesia_ad_position a ON y.external_id = 'profesia:' || a.num
                            JOIN profesia_position q ON q.slug = a.slug AND q.code IS NOT NULL
                            WHERE y.vacancy_id = v.vacancy_id AND y.closed_at IS NULL
                              AND y.external_id LIKE 'profesia:%')""";

    /** Открытые вакансии позиций profesia.sk ({@code profesia_ad_position}, номер — {@code profesia:<номер>}, V97). */
    private static final String POSITION_VACANCIES = """
            SELECT pp.slug, pp.name, pp.code, pp.mapped_at, jp.vacancy_id
            FROM profesia_position pp
            JOIN profesia_ad_position ap ON ap.slug = pp.slug
            JOIN job_posting jp ON jp.external_id LIKE 'profesia:%' AND jp.external_id = 'profesia:' || ap.num
                               AND jp.closed_at IS NULL""";

    /**
     * Позиции profesia.sk без группы ISCO-08, у которых есть открытые вакансии (§305): первыми — те, у которых больше
     * вакансий не находит ни один поиск (выбор группы даёт больше всего), затем — больше вакансий.
     *
     * @param limit сколько позиций
     * @return позиции без подсказки ({@code hints} пусто)
     */
    public List<ProfesiaPosition> unmappedProfesiaPositions(int limit) {
        return jdbcTemplate.query("WITH v AS (" + POSITION_VACANCIES + " WHERE pp.code IS NULL) "
                + "SELECT v.slug, v.name, count(DISTINCT v.vacancy_id) AS vacancies, "
                + "count(DISTINCT v.vacancy_id) FILTER (WHERE " + UNSEEN + ") AS unseen "
                + "FROM v GROUP BY v.slug, v.name ORDER BY unseen DESC, vacancies DESC, v.name LIMIT ?",
                (row, number) -> new ProfesiaPosition(row.getString(1), row.getString(2), row.getLong(3),
                        row.getLong(4), List.of()), limit);
    }

    /**
     * Позиции profesia.sk, группу которых выбрал администратор (§305) — чтобы исправить ошибку: недавние — первыми;
     * подсказка — выбранная группа («assigned» и дата); вакансии — открытые (позиции без открытых вакансий — с 0).
     *
     * @param limit сколько позиций
     * @return позиции с выбранной группой первой подсказкой
     */
    public List<ProfesiaPosition> assignedProfesiaPositions(int limit) {
        return jdbcTemplate.query("""
                SELECT pp.slug, pp.name, pp.code, n.name, to_char(pp.mapped_at, 'YYYY-MM-DD'),
                       count(DISTINCT jp.vacancy_id)
                FROM profesia_position pp
                JOIN occupation_group_name n ON n.code = pp.code AND n.language = 'en'
                LEFT JOIN profesia_ad_position ap ON ap.slug = pp.slug
                LEFT JOIN job_posting jp ON jp.external_id LIKE 'profesia:%' AND jp.external_id = 'profesia:' || ap.num
                                        AND jp.closed_at IS NULL
                WHERE pp.mapped_by = 'OWNER' AND pp.code IS NOT NULL
                GROUP BY pp.slug, pp.name, pp.code, n.name, pp.mapped_at
                ORDER BY pp.mapped_at DESC, pp.name
                LIMIT ?
                """, (row, number) -> new ProfesiaPosition(row.getString(1), row.getString(2), row.getLong(6), 0,
                List.of(new GroupHint(row.getString(3), row.getString(4), "assigned " + row.getString(5)))), limit);
    }

    /**
     * Подсказка 1 (§305): группы включённых позиций словаря, к которым название позиции profesia.sk отнесли бы правила
     * словаря: название позиции словаря или эквивалентное название (вид {@code TITLE}) — целыми словами в названии
     * («Architect» в «IT Architect»), или роль ({@code ROLE}) вместе с ключевым словом ({@code KEYWORD}) («Programmer»
     * и «Python» в «Python Programmer»); слово-исключение ({@code EXCLUDE}) позицию отменяет. Регистр не важен;
     * диакритика — как написана (в БД нет функции её снятия). Целое слово — {@code (^|[^[:alnum:]])…($|[^[:alnum:]])},
     * особые знаки слов словаря («c++», «.net») экранируются.
     *
     * @param title первый вариант названия позиции profesia.sk
     * @return группы с названием совпавшей позиции словаря
     */
    public List<GroupHint> dictionaryGroups(String title) {
        return jdbcTemplate.query("""
                WITH q AS (SELECT lower(CAST(? AS TEXT)) AS title),
                word AS (
                    SELECT t.position_id, t.kind,
                           '(^|[^[:alnum:]])' || regexp_replace(lower(t.term), '([.^$|?*+()\\[\\]{}\\\\])', '\\\\\\1', 'g')
                           || '($|[^[:alnum:]])' AS pattern
                    FROM position_term t
                    UNION ALL
                    SELECT p.id, 'TITLE',
                           '(^|[^[:alnum:]])' || regexp_replace(lower(p.name), '([.^$|?*+()\\[\\]{}\\\\])', '\\\\\\1', 'g')
                           || '($|[^[:alnum:]])'
                    FROM position p),
                hit AS (SELECT w.position_id, w.kind FROM word w, q WHERE q.title ~ w.pattern)
                SELECT DISTINCT ON (p.occupation_code) p.occupation_code, n.name, p.name
                FROM position p
                JOIN occupation_group_name n ON n.code = p.occupation_code AND n.language = 'en'
                WHERE p.active AND p.occupation_code IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM hit h WHERE h.position_id = p.id AND h.kind = 'EXCLUDE')
                  AND (EXISTS (SELECT 1 FROM hit h WHERE h.position_id = p.id AND h.kind = 'TITLE')
                       OR EXISTS (SELECT 1 FROM hit h WHERE h.position_id = p.id AND h.kind = 'ROLE')
                          AND EXISTS (SELECT 1 FROM hit h WHERE h.position_id = p.id AND h.kind = 'KEYWORD'))
                ORDER BY p.occupation_code, p.name
                """, (row, number) -> new GroupHint(row.getString(1), row.getString(2), "dictionary: " + row.getString(3)),
                title);
    }

    /**
     * Подсказка 2 (§305): группы, которые у открытых вакансий с этой позицией profesia.sk уже есть другим путём — другая
     * позиция того же объявления с группой, код портала, код по названию, позиция словаря; чаще — первыми (одна
     * вакансия — один голос за группу).
     *
     * @param slug  ключ позиции profesia.sk
     * @param limit сколько групп
     * @return группы с долей вакансий («12 of 31 ads»)
     */
    public List<GroupHint> neighbourGroups(String slug, int limit) {
        return jdbcTemplate.query("""
                WITH ads AS (
                    SELECT DISTINCT jp.vacancy_id FROM profesia_ad_position ap
                    JOIN job_posting jp ON jp.external_id LIKE 'profesia:%' AND jp.external_id = 'profesia:' || ap.num
                                       AND jp.closed_at IS NULL
                    WHERE ap.slug = ?),
                codes AS (
                    SELECT ads.vacancy_id, pp.code FROM ads
                    JOIN job_posting jp ON jp.vacancy_id = ads.vacancy_id AND jp.closed_at IS NULL
                                       AND jp.external_id LIKE 'profesia:%'
                    JOIN profesia_ad_position ap ON jp.external_id = 'profesia:' || ap.num
                    JOIN profesia_position pp ON pp.slug = ap.slug AND pp.code IS NOT NULL
                    UNION
                    SELECT ads.vacancy_id, jp.occupation_code FROM ads
                    JOIN job_posting jp ON jp.vacancy_id = ads.vacancy_id AND jp.closed_at IS NULL
                    WHERE jp.occupation_code IS NOT NULL
                    UNION
                    SELECT ads.vacancy_id, jp.title_occupation_code FROM ads
                    JOIN job_posting jp ON jp.vacancy_id = ads.vacancy_id AND jp.closed_at IS NULL
                    WHERE jp.title_occupation_code IS NOT NULL
                    UNION
                    SELECT ads.vacancy_id, p.occupation_code FROM ads
                    JOIN vacancy_position_match m ON m.vacancy_id = ads.vacancy_id
                    JOIN position p ON p.id = m.position_id WHERE p.occupation_code IS NOT NULL)
                SELECT c.code, n.name, count(DISTINCT c.vacancy_id) AS votes, (SELECT count(*) FROM ads) AS total
                FROM codes c JOIN occupation_group_name n ON n.code = c.code AND n.language = 'en'
                GROUP BY c.code, n.name ORDER BY votes DESC, c.code LIMIT ?
                """, (row, number) -> new GroupHint(row.getString(1), row.getString(2),
                row.getLong(3) + " of " + row.getLong(4) + " ads"), slug, limit);
    }

    /**
     * Группа позиции profesia.sk выбрана администратором (§305): {@code mapped_by = 'OWNER'} — распознавание
     * воркера её больше не меняет ({@code TitleClassificationRepository}); объявления с ней находятся условиями этой
     * группы сразу (представление {@code search_condition_match}).
     *
     * @param slug  ключ позиции
     * @param group группа ISCO-08
     * @return {@code false} — такой позиции нет
     */
    public boolean mapProfesiaPosition(String slug, String group) {
        return jdbcTemplate.update("UPDATE profesia_position SET code = ?, mapped_by = 'OWNER', mapped_at = now() "
                + "WHERE slug = ?", group, slug) == 1;
    }

    /**
     * @param code код
     * @return {@code true} — группа ISCO-08 (4 цифры) справочника
     */
    public boolean isGroup(String code) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM occupation_group WHERE level = 4 AND code = ?)", Boolean.class, code));
    }

    /**
     * Названия позиции заменяются новыми; порядок внутри вида сохраняется (первым совпавшим названием объясняется
     * соответствие).
     *
     * @param id    позиция
     * @param terms названия по видам, без повторов внутри вида
     */
    public void replaceTerms(long id, Terms terms) {
        jdbcTemplate.update("DELETE FROM position_term WHERE position_id = ?", id);
        insertTerms(id, TermKind.TITLE, terms.titles());
        insertTerms(id, TermKind.ROLE, terms.roles());
        insertTerms(id, TermKind.KEYWORD, terms.keywords());
        insertTerms(id, TermKind.EXCLUDE, terms.exclude());
    }

    /**
     * Повтор без учёта регистра пропускается по уникальному индексу {@code (position_id, kind, lower(term))}: решает
     * {@code lower} PostgreSQL, а не Java ({@code ON CONFLICT DO NOTHING}; аудит §165) — на редких буквах (например,
     * «İ») правила регистра расходятся, и повтор не становится ошибкой.
     */
    private void insertTerms(long id, TermKind kind, List<String> terms) {
        terms.forEach(term -> jdbcTemplate.update("INSERT INTO position_term (position_id, kind, term) VALUES (?, ?, ?) "
                + "ON CONFLICT DO NOTHING", id, kind.name(), term));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    /**
     * Значение с номером версии словаря.
     *
     * @param version номер версии
     * @param value   значение
     * @param <T>     тип значения
     */
    public record Versioned<T>(int version, T value) {
    }

    /** Вид названия позиции ({@code position_term.kind}). */
    private enum TermKind { TITLE, ROLE, KEYWORD, EXCLUDE }

    /** Позиция, пока строки читаются. */
    private record Row(String code, String name, String group, boolean active, Map<TermKind, List<String>> terms) {

        List<String> of(TermKind kind) {
            return List.copyOf(terms.getOrDefault(kind, List.of()));
        }
    }
}
