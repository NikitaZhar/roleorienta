package com.roleorienta.worker.match;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Позиции словаря и предрасчитанные соответствия вакансий (JDBC).
 */
@Repository
public class MatchRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public MatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Позиции словаря в таблице {@code position}: новые добавляются, названия обновляются.
     *
     * @param dictionary словарь
     * @return id позиций по коду
     */
    @Transactional
    public Map<String, Long> syncPositions(PositionDictionary dictionary) {
        Map<String, Long> ids = new HashMap<>();
        for (PositionDictionary.Position position : dictionary.positions()) {
            ids.put(position.code(), jdbcTemplate.queryForObject("""
                    INSERT INTO position (code, name) VALUES (?, ?)
                    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name RETURNING id
                    """, Long.class, position.code(), position.name()));
        }
        return ids;
    }

    /**
     * @return страна города по нормализованному названию; при одинаковом названии — самого
     *         населённого (GeoNames)
     */
    public Map<String, String> cities() {
        Map<String, String> cities = new HashMap<>();
        jdbcTemplate.query("SELECT DISTINCT ON (name) name, country FROM geo_city ORDER BY name, population DESC",
                row -> {
                    cities.put(row.getString("name"), row.getString("country"));
                });
        return cities;
    }

    /**
     * @param version версия сопоставления
     * @param limit   сколько вакансий
     * @return незакрытые вакансии, не сопоставленные по этой версии: название, тексты и места публикаций
     */
    public List<VacancyText> vacanciesToMatch(String version, int limit) {
        return jdbcTemplate.query("""
                SELECT v.id, v.title, v.match_version, string_agg(coalesce(p.content, ''), ' ') AS content,
                       array_agg(p.location) AS locations
                FROM vacancy v JOIN job_posting p ON p.vacancy_id = v.id
                WHERE v.state <> 'CLOSED' AND v.match_version IS DISTINCT FROM ?
                GROUP BY v.id, v.title, v.match_version ORDER BY v.id LIMIT ?
                """, (row, number) -> new VacancyText(row.getLong("id"), row.getString("title"),
                row.getString("content"), row.getString("match_version"), strings(row.getArray("locations"))),
                version, limit);
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    /**
     * Соответствия вакансии заменяются одной транзакцией; версия записывается, только если вакансия
     * не изменилась с чтения — иначе откат, вакансия сопоставится следующим проходом.
     *
     * @param vacancy   вакансия, как прочитана
     * @param version   версия сопоставления
     * @param positions id позиции → объяснение
     * @param facts     страны и формат работы
     * @throws OptimisticLockingFailureException вакансия изменилась
     */
    @Transactional
    public void saveMatches(VacancyText vacancy, String version, Map<Long, String> positions,
            LocationResolver.LocationFacts facts) {
        int updated = jdbcTemplate.update("""
                UPDATE vacancy SET match_version = ?, work_countries = string_to_array(?, ','),
                                   country_uncertain = ?, work_format = ?
                WHERE id = ? AND match_version IS NOT DISTINCT FROM ?
                """, version, String.join(",", facts.countries().stream().sorted().toList()), facts.uncertain(),
                facts.format() == null ? null : facts.format().name(), vacancy.id(), vacancy.matchVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Vacancy " + vacancy.id() + " changed while matching");
        }
        jdbcTemplate.update("DELETE FROM vacancy_position_match WHERE vacancy_id = ?", vacancy.id());
        positions.forEach((positionId, explanation) -> jdbcTemplate.update("""
                INSERT INTO vacancy_position_match (vacancy_id, position_id, dictionary_version, explanation)
                VALUES (?, ?, ?, ?)
                """, vacancy.id(), positionId, version, explanation));
    }

    /**
     * Вакансия к сопоставлению.
     *
     * @param id           вакансия
     * @param title        название
     * @param content      тексты публикаций (HTML)
     * @param matchVersion версия, по которой сопоставлена; {@code null} — не сопоставлялась
     * @param locations    места публикаций ({@code null} — не указано)
     */
    public record VacancyText(long id, String title, String content, String matchVersion, List<String> locations) {
    }
}
