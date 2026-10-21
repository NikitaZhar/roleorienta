package com.roleorienta.worker.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Словарь позиций для тестов — файл {@code positions.json} тестовых ресурсов (до §159 он был словарём приложения;
 * рабочий словарь теперь в БД и меняется администратором, а тесты правил сопоставления опираются на неизменный
 * набор).
 */
final class TestDictionary {

    /** Номер версии, которую тест записывает в БД: версия сопоставления начинается с {@code d1/}. */
    static final int VERSION = 1;

    private static final String RESOURCE = "/positions.json";
    private static final Map<String, String> KINDS = Map.of("titles", "TITLE", "roles", "ROLE", "keywords",
            "KEYWORD", "exclude", "EXCLUDE");

    private TestDictionary() {
    }

    /**
     * @return словарь из файла (без БД)
     */
    static PositionDictionary fromFile() {
        JsonNode file = read();
        List<PositionDictionary.Position> positions = new ArrayList<>();
        for (JsonNode position : file.path("positions")) {
            positions.add(new PositionDictionary.Position(position.path("code").asText(),
                    position.path("name").asText(), terms(position.path("titles")),
                    new PositionDictionary.Specialization(terms(position.path("roles")),
                            terms(position.path("keywords"))), terms(position.path("exclude"))));
        }
        return new PositionDictionary(MatchRepository.VERSION_PREFIX + VERSION, file.path("contentHits").asInt(),
                terms(file.path("otherSpecializations")), positions);
    }

    /**
     * Записывает словарь из файла в БД вместо имеющегося: позиции по коду, их названия, общие настройки с версией
     * {@link #VERSION}. Таблицы соответствий и условий тест чистит сам до вызова.
     *
     * @param jdbcTemplate доступ к БД
     */
    static void store(JdbcTemplate jdbcTemplate) {
        JsonNode file = read();
        jdbcTemplate.update("DELETE FROM position_term");
        List<String> others = new ArrayList<>();
        file.path("otherSpecializations").forEach(term -> others.add(term.asText()));
        jdbcTemplate.update("UPDATE position_dictionary SET version = ?, content_hits = ?, "
                + "other_specializations = string_to_array(?, '|') WHERE id = 1", VERSION,
                file.path("contentHits").asInt(), String.join("|", others));
        for (JsonNode position : file.path("positions")) {
            long id = jdbcTemplate.queryForObject("""
                    INSERT INTO position (code, name) VALUES (?, ?)
                    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name RETURNING id
                    """, Long.class, position.path("code").asText(), position.path("name").asText());
            KINDS.forEach((field, kind) -> position.path(field).forEach(term -> jdbcTemplate.update(
                    "INSERT INTO position_term (position_id, kind, term) VALUES (?, ?, ?)", id, kind, term.asText())));
        }
    }

    private static List<PositionDictionary.Term> terms(JsonNode values) {
        List<PositionDictionary.Term> terms = new ArrayList<>();
        values.forEach(value -> terms.add(PositionDictionary.term(value.asText())));
        return terms;
    }

    private static JsonNode read() {
        try (InputStream input = TestDictionary.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Test dictionary " + RESOURCE + " is missing");
            }
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("Test dictionary " + RESOURCE + " is not readable", exception);
        }
    }
}
