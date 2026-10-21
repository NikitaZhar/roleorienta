package com.roleorienta.worker.match;

import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Словарь позиций и предрасчитанные соответствия вакансий (JDBC).
 */
@Repository
public class MatchRepository {

    /** Версия словаря в версии сопоставления: {@code d<номер>} (§159). */
    static final String VERSION_PREFIX = "d";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public MatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Словарь позиций из БД одним снимком данных (уровень {@code REPEATABLE READ}: правка администратора между
     * запросами не смешивает версии; стенограмма §159). Позиция без названий в словаре не участвует — ей нечем
     * совпасть.
     *
     * @return словарь и id его позиций по коду
     * @throws IllegalStateException нет строки настроек словаря ({@code position_dictionary})
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StoredDictionary dictionary() {
        Settings settings = jdbcTemplate.query(
                "SELECT version, content_hits, other_specializations FROM position_dictionary WHERE id = 1",
                (row, number) -> new Settings(row.getInt("version"), row.getInt("content_hits"),
                        strings(row.getArray("other_specializations")))).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Position dictionary settings are missing"));
        Map<Long, PositionTerms> byId = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT p.id, p.code, p.name, t.kind, t.term FROM position p JOIN position_term t ON t.position_id = p.id
                ORDER BY p.id, t.id
                """, row -> {
                    String code = row.getString("code");
                    String name = row.getString("name");
                    TermKind kind = TermKind.valueOf(row.getString("kind"));
                    PositionDictionary.Term term = PositionDictionary.term(row.getString("term"));
                    byId.computeIfAbsent(row.getLong("id"),
                            id -> new PositionTerms(code, name, new EnumMap<>(TermKind.class)))
                            .terms().computeIfAbsent(kind, any -> new ArrayList<>()).add(term);
                });
        List<PositionDictionary.Position> positions = new ArrayList<>();
        Map<String, Long> ids = new HashMap<>();
        byId.forEach((id, position) -> {
            ids.put(position.code(), id);
            positions.add(new PositionDictionary.Position(position.code(), position.name(),
                    position.of(TermKind.TITLE), new PositionDictionary.Specialization(position.of(TermKind.ROLE),
                            position.of(TermKind.KEYWORD)), position.of(TermKind.EXCLUDE)));
        });
        return new StoredDictionary(new PositionDictionary(VERSION_PREFIX + settings.version(), settings.contentHits(),
                settings.others().stream().map(PositionDictionary::term).toList(), positions), Map.copyOf(ids));
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
     * @return незакрытые вакансии, не сопоставленные по этой версии: название, тексты и места публикаций, страна
     *         государственного портала, если вакансия пришла и с него (§149)
     */
    public List<VacancyText> vacanciesToMatch(String version, int limit) {
        return jdbcTemplate.query("""
                SELECT v.id, v.title, v.match_version, string_agg(coalesce(p.content, ''), ' ') AS content,
                       array_agg(p.location) AS locations,
                       max(s.country) FILTER (WHERE s.provider = ?) AS portal_country
                FROM vacancy v JOIN job_posting p ON p.vacancy_id = v.id JOIN source s ON s.id = p.source_id
                WHERE v.state <> 'CLOSED' AND v.match_version IS DISTINCT FROM ?
                GROUP BY v.id, v.title, v.match_version ORDER BY v.id LIMIT ?
                """, (row, number) -> new VacancyText(row.getLong("id"), row.getString("title"),
                row.getString("content"), row.getString("match_version"),
                new Places(strings(row.getArray("locations")), row.getString("portal_country"))),
                StatePortalAdapter.PROVIDER, version, limit);
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    /**
     * Соответствия и требования к языку вакансии заменяются одной транзакцией; версия записывается, только если вакансия
     * не изменилась с чтения — иначе откат, вакансия сопоставится следующим проходом.
     *
     * @param vacancy   вакансия, как прочитана
     * @param version   версия сопоставления
     * @param positions id позиции → объяснение
     * @param facts     страны и формат работы
     * @param languages требования к языку (§158)
     * @throws OptimisticLockingFailureException вакансия изменилась
     */
    @Transactional
    public void saveMatches(VacancyText vacancy, String version, Map<Long, String> positions,
            LocationResolver.LocationFacts facts, List<LanguageRequirements.Requirement> languages) {
        int updated = jdbcTemplate.update("""
                UPDATE vacancy SET match_version = ?, work_countries = string_to_array(?, ','),
                                   work_format = ?
                WHERE id = ? AND match_version IS NOT DISTINCT FROM ?
                """, version, String.join(",", facts.countries().stream().sorted().toList()),
                facts.format() == null ? null : facts.format().name(), vacancy.id(), vacancy.matchVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Vacancy " + vacancy.id() + " changed while matching");
        }
        jdbcTemplate.update("DELETE FROM vacancy_position_match WHERE vacancy_id = ?", vacancy.id());
        positions.forEach((positionId, explanation) -> jdbcTemplate.update("""
                INSERT INTO vacancy_position_match (vacancy_id, position_id, dictionary_version, explanation)
                VALUES (?, ?, ?, ?)
                """, vacancy.id(), positionId, version, explanation));
        jdbcTemplate.update("DELETE FROM vacancy_language WHERE vacancy_id = ?", vacancy.id());
        languages.forEach(language -> jdbcTemplate.update(
                "INSERT INTO vacancy_language (vacancy_id, language, required) VALUES (?, ?, ?)", vacancy.id(),
                language.language(), language.required()));
    }

    /**
     * Вакансия к сопоставлению.
     *
     * @param id           вакансия
     * @param title        название
     * @param content      тексты публикаций (HTML)
     * @param matchVersion версия, по которой сопоставлена; {@code null} или {@code reset:…} — не
     *                     сопоставлялась или изменилась
     * @param places       места публикаций и страна портала
     */
    public record VacancyText(long id, String title, String content, String matchVersion, Places places) {
    }

    /**
     * Места вакансии.
     *
     * @param locations     места публикаций ({@code null} — не указано)
     * @param portalCountry страна государственного портала, если вакансия пришла и с него; иначе {@code null}
     */
    public record Places(List<String> locations, String portalCountry) {
    }

    /**
     * Словарь из БД и id его позиций.
     *
     * @param words       словарь
     * @param positionIds id позиции по коду
     */
    public record StoredDictionary(PositionDictionary words, Map<String, Long> positionIds) {
    }

    /** Вид названия позиции ({@code position_term.kind}). */
    private enum TermKind { TITLE, ROLE, KEYWORD, EXCLUDE }

    /** Общие настройки словаря. */
    private record Settings(int version, int contentHits, List<String> others) {
    }

    /** Позиция и её названия по видам, пока строки читаются. */
    private record PositionTerms(String code, String name, Map<TermKind, List<PositionDictionary.Term>> terms) {

        List<PositionDictionary.Term> of(TermKind kind) {
            return terms.getOrDefault(kind, List.of());
        }
    }
}
