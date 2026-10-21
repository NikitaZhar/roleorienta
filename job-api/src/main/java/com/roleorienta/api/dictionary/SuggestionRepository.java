package com.roleorienta.api.dictionary;

import com.roleorienta.api.dictionary.SuggestionDtos.AdminSuggestionItem;
import com.roleorienta.api.dictionary.SuggestionDtos.Decision;
import com.roleorienta.api.dictionary.SuggestionDtos.State;
import com.roleorienta.api.dictionary.SuggestionDtos.SuggestionItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Предложения позиций (JDBC; стенограмма §161).
 */
@Repository
public class SuggestionRepository {

    /** Поля предложения с добавленной позицией. */
    private static final String COLUMNS = """
            s.id, s.text, s.state, s.reason, s.created_at, s.decided_at, p.code, p.name
            FROM position_suggestion s LEFT JOIN position p ON p.id = s.position_id""";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public SuggestionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param userId пользователь
     * @return сколько предложений за последние сутки
     */
    public int countLastDay(long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM position_suggestion WHERE user_id = ? "
                + "AND created_at > now() - interval '1 day'", Integer.class, userId);
    }

    /**
     * @param userId пользователь
     * @param text   текст предложения
     * @return предложение на рассмотрении
     */
    public SuggestionItem insert(long userId, String text) {
        long id = jdbcTemplate.queryForObject("INSERT INTO position_suggestion (user_id, text, state) "
                + "VALUES (?, ?, 'PENDING') RETURNING id", Long.class, userId, text);
        return jdbcTemplate.queryForObject("SELECT " + COLUMNS + " WHERE s.id = ?",
                (row, number) -> item(row), id);
    }

    /**
     * @param userId пользователь
     * @return его предложения, новые сверху
     */
    public List<SuggestionItem> byUser(long userId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " WHERE s.user_id = ? ORDER BY s.created_at DESC, s.id DESC",
                (row, number) -> item(row), userId);
    }

    /**
     * @return все предложения: на рассмотрении — первыми, затем новые сверху; с почтой пользователя
     */
    public List<AdminSuggestionItem> all() {
        return jdbcTemplate.query("SELECT u.email, " + COLUMNS + " JOIN user_account u ON u.id = s.user_id "
                + "ORDER BY s.state <> 'PENDING', s.created_at DESC, s.id DESC",
                (row, number) -> new AdminSuggestionItem(item(row), row.getString("email")));
    }

    /**
     * @param code код позиции
     * @return id позиции словаря с этим кодом (и выключенной); пусто — нет
     */
    public Optional<Long> positionId(String code) {
        return jdbcTemplate.queryForList("SELECT id FROM position WHERE code = ?", Long.class, code).stream()
                .findFirst();
    }

    /**
     * Решение администратора; прежнее решение заменяется.
     *
     * @param id         предложение
     * @param state      {@code ADDED} или {@code REJECTED}
     * @param positionId добавленная позиция — для {@code ADDED}, иначе {@code null}
     * @param reason     причина — для {@code REJECTED}, иначе {@code null}
     * @return {@code false} — предложения нет
     */
    public boolean decide(long id, State state, Long positionId, String reason) {
        return jdbcTemplate.update("UPDATE position_suggestion SET state = ?, position_id = ?, reason = ?, "
                + "decided_at = now() WHERE id = ?", state.name(), positionId, reason, id) == 1;
    }

    private static SuggestionItem item(ResultSet row) throws SQLException {
        return new SuggestionItem(row.getLong("id"), row.getString("text"),
                new Decision(State.valueOf(row.getString("state")), row.getString("code"), row.getString("name"),
                        row.getString("reason"), instant(row, "decided_at")),
                instant(row, "created_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
