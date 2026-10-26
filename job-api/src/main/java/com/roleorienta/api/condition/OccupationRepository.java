package com.roleorienta.api.condition;

import com.roleorienta.api.condition.ConditionDtos.CatalogItem;
import com.roleorienta.api.condition.ConditionDtos.Direction;
import com.roleorienta.api.condition.ConditionDtos.GroupNode;
import com.roleorienta.api.condition.ConditionDtos.PositionOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.i18n.LocaleContextHolder;
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
     * Язык текущего запроса: Spring MVC кладёт язык заголовка {@code Accept-Language} в {@code LocaleContextHolder} на
     * время обработки запроса — параметр {@code Locale} в каждом методе контроллера не нужен
     * (https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/localeresolver.html).
     *
     * @return язык названий: {@code ru} или {@code en} (языки интерфейса)
     */
    public static String language() {
        return "ru".equals(LocaleContextHolder.getLocale().getLanguage()) ? "ru" : "en";
    }

    /**
     * Подсказка при вводе: сначала включённые позиции словаря, в названии или коде которых есть строка, затем группы, в
     * названии которых на любом языке или в названии одной из профессий ESCO группы (синонимы, в том числе словацкие)
     * есть строка; среди групп выше те, где строка — в названии на языке интерфейса. Регистр не важен.
     *
     * @param query    строка
     * @param language язык названий групп
     * @return не больше {@value #SUGGESTIONS} вариантов
     */
    public List<PositionOption> suggestions(String query, String language) {
        return jdbcTemplate.query("""
                WITH q AS (SELECT lower(CAST(? AS TEXT)) AS text, CAST(? AS TEXT) AS language)
                SELECT code, name, grp FROM (
                    SELECT p.code, p.name, false AS grp, 0 AS rank
                    FROM position p, q
                    WHERE p.active AND (strpos(lower(p.name), q.text) > 0 OR strpos(lower(p.code), q.text) > 0)
                    UNION ALL
                    SELECT g.code, g.name, true, CASE WHEN strpos(lower(g.name), q.text) > 0 THEN 1 ELSE 2 END
                    FROM (SELECT g.code, """ + GROUP_NAME + """
                           AS name FROM occupation_group g, q WHERE g.level = 4) g, q
                    WHERE EXISTS (SELECT 1 FROM occupation_group_name n
                                  WHERE n.code = g.code AND strpos(lower(n.name), q.text) > 0)
                       OR EXISTS (SELECT 1 FROM esco_occupation e JOIN esco_label l ON l.occupation_id = e.id
                                  WHERE e.group_code = g.code AND strpos(lower(l.label), q.text) > 0)
                ) found ORDER BY rank, name LIMIT ?
                """, (row, number) -> new PositionOption(row.getString("code"), row.getString("name"),
                row.getBoolean("grp")), query.strip(), language, SUGGESTIONS);
    }

    /**
     * Все позиции для просмотра: направления (подразделы ISCO-08, 2 цифры) → группы (4 цифры) → включённые позиции
     * словаря группы; по коду, позиции — по названию.
     *
     * @param language язык названий
     * @return направления
     */
    public List<Direction> catalog(String language) {
        Map<String, List<CatalogItem>> positions = new HashMap<>();
        jdbcTemplate.query("SELECT occupation_code, code, name FROM position WHERE active AND occupation_code IS NOT NULL "
                + "ORDER BY name", row -> {
                    positions.computeIfAbsent(row.getString(1), any -> new ArrayList<>())
                            .add(new CatalogItem(row.getString(2), row.getString(3)));
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
     * @param codes коды
     * @return какие из них — группы ISCO-08 (4 цифры) справочника
     */
    public Set<String> knownGroups(Collection<String> codes) {
        return new HashSet<>(jdbcTemplate.queryForList("SELECT code FROM occupation_group WHERE level = 4 AND code = ANY(?)",
                String.class, (Object) codes.toArray(String[]::new)));
    }
}
