package com.roleorienta.api.dictionary;

import com.roleorienta.api.dictionary.DictionaryDtos.PositionView;
import com.roleorienta.api.dictionary.DictionaryDtos.Settings;
import com.roleorienta.api.dictionary.DictionaryDtos.Terms;
import java.sql.Array;
import java.sql.SQLException;
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
                SELECT p.id, p.code, p.name, p.active, t.kind, t.term
                FROM position p LEFT JOIN position_term t ON t.position_id = p.id
                ORDER BY p.name, p.id, t.id
                """, row -> {
                    long id = row.getLong("id");
                    String code = row.getString("code");
                    String name = row.getString("name");
                    boolean active = row.getBoolean("active");
                    String kind = row.getString("kind");
                    String term = row.getString("term");
                    Row position = rows.computeIfAbsent(id, any -> new Row(code, name, active,
                            new EnumMap<>(TermKind.class)));
                    if (kind != null) {
                        position.terms().computeIfAbsent(TermKind.valueOf(kind), any -> new ArrayList<>()).add(term);
                    }
                });
        List<PositionView> positions = new ArrayList<>();
        rows.forEach((id, row) -> positions.add(new PositionView(id, row.code(), row.name(), row.active(),
                new Terms(row.of(TermKind.TITLE), row.of(TermKind.ROLE), row.of(TermKind.KEYWORD),
                        row.of(TermKind.EXCLUDE)))));
        return positions;
    }

    /**
     * @param code код
     * @param name название
     * @return id новой позиции; пусто — позиция с таким кодом уже есть
     */
    public Optional<Long> insertPosition(String code, String name) {
        return jdbcTemplate.queryForList("""
                INSERT INTO position (code, name) VALUES (?, ?) ON CONFLICT (code) DO NOTHING RETURNING id
                """, Long.class, code, name).stream().findFirst();
    }

    /**
     * @param id     позиция
     * @param name   название
     * @param active включена
     * @return {@code false} — позиции нет
     */
    public boolean updatePosition(long id, String name, boolean active) {
        return jdbcTemplate.update("UPDATE position SET name = ?, active = ? WHERE id = ?", name, active, id) == 1;
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

    private void insertTerms(long id, TermKind kind, List<String> terms) {
        terms.forEach(term -> jdbcTemplate.update(
                "INSERT INTO position_term (position_id, kind, term) VALUES (?, ?, ?)", id, kind.name(), term));
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
    private record Row(String code, String name, boolean active, Map<TermKind, List<String>> terms) {

        List<String> of(TermKind kind) {
            return List.copyOf(terms.getOrDefault(kind, List.of()));
        }
    }
}
