package com.roleorienta.api.condition;

import com.roleorienta.api.condition.ConditionDtos.CatalogPosition;
import com.roleorienta.api.condition.ConditionDtos.Direction;
import com.roleorienta.api.condition.ConditionDtos.GroupNode;
import com.roleorienta.api.condition.ConditionDtos.PositionOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Выбор позиции в условиях (§204): позиции словаря и группы ISCO-08 (4 цифры) с названиями на языке интерфейса
 * (справочник V75; нет названия на этом языке — английское). JDBC.
 */
@Repository
public class OccupationRepository {

    /** Сколько вариантов подсказки при вводе. */
    static final int SUGGESTIONS = 20;

    /** Наибольшая длина запомненного запроса без результата ({@code position_search_miss.query}). */
    private static final int MAX_MISS = 100;

    /**
     * Начало слова: начало строки или знак, не буква и не цифра. Не {@code \m} — он совпадает только перед буквой или
     * цифрой, и запрос «.net» не находил бы «.NET developer» (аудит §211); то же правило — в поиске по каталогу
     * ({@code PositionCatalog.wordStart}).
     */
    private static final String WORD_START = "(^|[^[:alnum:]])";

    /** Знаки, особые в регулярном выражении PostgreSQL: в строке пользователя экранируются. */
    private static final Pattern REGEX_SPECIAL = Pattern.compile("[\\\\.^$|?*+()\\[\\]{}]");

    /** Название группы {@code g.code} на языке {@code q.language}, иначе английское. */
    private static final String GROUP_NAME = """
            coalesce((SELECT n.name FROM occupation_group_name n WHERE n.code = g.code AND n.language = q.language),
                     (SELECT n.name FROM occupation_group_name n WHERE n.code = g.code AND n.language = 'en'))""";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public OccupationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Подсказка при вводе: сначала включённые позиции словаря, в названии, коде или эквивалентном названии которых
     * (вид {@code TITLE}: «software architect» у позиции «Architect») есть строка, затем группы, в названии которых на
     * любом языке или в названии одной из профессий ESCO группы (синонимы, в том числе словацкие) есть строка; среди
     * групп выше те, где строка — в названии на языке интерфейса. Строка ищется с начала слова ({@link #WORD_START}):
     * «pyt» находит «Python», но не «kopytá» и «dopytu» внутри словацких синонимов; «.net» — «.NET developer». Регистр
     * не важен ({@code ~*}, https://www.postgresql.org/docs/16/functions-matching.html#FUNCTIONS-POSIX-REGEXP). Если
     * строка нашлась не в показанном названии, вариант несёт совпавшее (§207): «Architect — software architect»;
     * порядок у группы — синоним ESCO на языке интерфейса, название группы на другом языке (сначала английское), иной
     * синоним ESCO (сначала английский); среди равных — основной, короче, по алфавиту. Названия ESCO просматриваются
     * один раз за запрос ({@code label_hit}), а не по разу на каждую из 436 групп (аудит §211).
     *
     * @param query    строка
     * @param language язык названий групп
     * @return не больше {@value #SUGGESTIONS} вариантов
     */
    public List<PositionOption> suggestions(String query, String language) {
        return jdbcTemplate.query("""
                WITH q AS (SELECT CAST(? AS TEXT) AS word, CAST(? AS TEXT) AS language),
                label_hit AS (
                    SELECT e.group_code AS code, l.label, l.language, l.preferred
                    FROM esco_label l JOIN esco_occupation e ON e.id = l.occupation_id, q
                    WHERE l.label ~* q.word),
                name_hit AS (
                    SELECT n.code, n.name, n.language FROM occupation_group_name n, q
                    WHERE length(n.code) = 4 AND n.name ~* q.word),
                hit AS (SELECT code FROM label_hit UNION SELECT code FROM name_hit),
                hit_group AS (
                    SELECT g.code, """ + GROUP_NAME + """
                     AS name FROM occupation_group g JOIN hit ON hit.code = g.code, q WHERE g.level = 4)
                SELECT code, name, grp, matched FROM (
                    SELECT p.code, p.name, false AS grp, 0 AS rank,
                           CASE WHEN p.name ~* q.word OR p.code ~* q.word THEN NULL
                                ELSE (SELECT t.term FROM position_term t
                                      WHERE t.position_id = p.id AND t.kind = 'TITLE' AND t.term ~* q.word
                                      ORDER BY t.id LIMIT 1) END AS matched
                    FROM position p, q
                    WHERE p.active AND (p.name ~* q.word OR p.code ~* q.word
                                        OR EXISTS (SELECT 1 FROM position_term t WHERE t.position_id = p.id
                                                   AND t.kind = 'TITLE' AND t.term ~* q.word))
                    UNION ALL
                    SELECT g.code, g.name, true, CASE WHEN g.name ~* q.word THEN 1 ELSE 2 END,
                           CASE WHEN g.name ~* q.word THEN NULL
                                ELSE coalesce(
                                    (SELECT h.label FROM label_hit h WHERE h.code = g.code AND h.language = q.language
                                     ORDER BY h.preferred DESC, length(h.label), h.label LIMIT 1),
                                    (SELECT h.name FROM name_hit h WHERE h.code = g.code
                                     ORDER BY h.language = 'en' DESC, h.language, h.name LIMIT 1),
                                    (SELECT h.label FROM label_hit h WHERE h.code = g.code
                                     ORDER BY h.language = 'en' DESC, h.preferred DESC, length(h.label), h.label
                                     LIMIT 1)) END
                    FROM hit_group g, q
                ) found ORDER BY rank, name LIMIT ?
                """, (row, number) -> new PositionOption(row.getString("code"), row.getString("name"),
                row.getBoolean("grp"), row.getString("matched")),
                WORD_START + REGEX_SPECIAL.matcher(query.strip()).replaceAll("\\\\$0"), language, SUGGESTIONS);
    }

    /**
     * Все позиции для просмотра: направления (подразделы ISCO-08, 2 цифры) → группы (4 цифры) → включённые позиции
     * словаря группы с эквивалентными названиями (для поиска в каталоге, §208); по коду, позиции — по названию.
     *
     * @param language язык названий
     * @return направления
     */
    public List<Direction> catalog(String language) {
        Map<String, List<CatalogPosition>> positions = new HashMap<>();
        jdbcTemplate.query("""
                SELECT p.occupation_code, p.code, p.name,
                       coalesce((SELECT array_agg(t.term ORDER BY t.id) FROM position_term t
                                 WHERE t.position_id = p.id AND t.kind = 'TITLE'), ARRAY[]::VARCHAR[]) AS titles
                FROM position p WHERE p.active AND p.occupation_code IS NOT NULL ORDER BY p.name
                """, row -> {
                    positions.computeIfAbsent(row.getString(1), any -> new ArrayList<>())
                            .add(new CatalogPosition(row.getString(2), row.getString(3),
                                    List.of((String[]) row.getArray("titles").getArray())));
                });
        Map<String, List<GroupNode>> groups = new HashMap<>();
        jdbcTemplate.query("WITH q AS (SELECT CAST(? AS TEXT) AS language) SELECT g.code, " + GROUP_NAME
                + " FROM occupation_group g, q WHERE g.level = 4 ORDER BY g.code", row -> {
                    String code = row.getString(1);
                    groups.computeIfAbsent(code.substring(0, 2), any -> new ArrayList<>())
                            .add(new GroupNode(code, row.getString(2), positions.getOrDefault(code, List.of())));
                }, language);
        return jdbcTemplate.query("WITH q AS (SELECT CAST(? AS TEXT) AS language) SELECT g.code, " + GROUP_NAME
                + " FROM occupation_group g, q WHERE g.level = 2 ORDER BY g.code",
                (row, number) -> new Direction(row.getString(1), row.getString(2),
                        groups.getOrDefault(row.getString(1), List.of())), language);
    }

    /**
     * @param codes    коды групп
     * @param language язык названий
     * @return названия известных групп (4 цифры) по коду; неизвестных кодов среди ключей нет
     */
    public Map<String, String> groupNames(Collection<String> codes, String language) {
        Map<String, String> names = new LinkedHashMap<>();
        jdbcTemplate.query("WITH q AS (SELECT CAST(? AS TEXT) AS language) SELECT g.code, " + GROUP_NAME
                + " FROM occupation_group g, q WHERE g.level = 4 AND g.code = ANY(?) ORDER BY g.code", row -> {
                    names.put(row.getString(1), row.getString(2));
                }, language, codes.toArray(String[]::new));
        return names;
    }

    /**
     * Запрос подсказки ничего не нашёл (§205) — запоминается для администратора: строка без пробелов по краям и
     * повторов пробелов, в нижнем регистре, не длиннее 100 знаков; короче 2 знаков — не запоминается. Пока пользователь
     * печатает, подсказка запрашивается после паузы, и начала одного слова («rust», «rust d») ничего не находят по
     * очереди: такое начало, впервые записанное меньше минуты назад, удаляется, остаётся полная строка.
     *
     * @param query строка подсказки
     */
    public void recordMiss(String query) {
        String text = query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        // Длина — в символах, как у PostgreSQL (length, VARCHAR), а не в единицах UTF-16 Java: эмодзи — один символ
        // (аудит §211: CHECK length >= 2 не нарушается).
        int symbols = text.codePointCount(0, text.length());
        if (symbols < 2) {
            return;
        }
        String miss = symbols > MAX_MISS ? text.substring(0, text.offsetByCodePoints(0, MAX_MISS)) : text;
        jdbcTemplate.update("""
                DELETE FROM position_search_miss
                WHERE misses = 1 AND last_at > now() - interval '1 minute' AND query <> ? AND strpos(?, query) = 1
                """, miss, miss);
        jdbcTemplate.update("""
                INSERT INTO position_search_miss (query, misses, last_at) VALUES (?, 1, now())
                ON CONFLICT (query) DO UPDATE SET misses = position_search_miss.misses + 1, last_at = now()
                """, miss);
    }

    /**
     * @param codes коды
     * @return какие из них — группы ISCO-08 (4 цифры) справочника
     */
    public Set<String> knownGroups(Collection<String> codes) {
        return new HashSet<>(jdbcTemplate.queryForList("SELECT code FROM occupation_group WHERE level = 4 AND code = ANY(?)",
                String.class, (Object) codes.toArray(String[]::new)));
    }
}
