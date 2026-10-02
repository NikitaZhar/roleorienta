package com.roleorienta.worker.outbox;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Доступ к таблице {@code outbox_event}. Только запросы, без логики.
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к общей базе PostgreSQL
     */
    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Захватывает до {@code limit} неопубликованных событий по порядку id.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} блокирует выбранные строки до конца транзакции и
     * пропускает строки, уже заблокированные другой репликой, — каждое событие отправляет ровно
     * один публикатор. Должен вызываться внутри транзакции.
     * https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE</p>
     *
     * @param limit максимум событий
     * @return захваченные события; пустой список, если публиковать нечего
     */
    public List<OutboxEvent> claimUnpublished(int limit) {
        return jdbcTemplate.query("""
                SELECT e.id, e.task_id, t.type
                FROM outbox_event e JOIN task t ON t.id = e.task_id
                WHERE e.published_at IS NULL
                ORDER BY e.id
                LIMIT ?
                FOR UPDATE OF e SKIP LOCKED
                """,
                (row, rowNum) -> new OutboxEvent(row.getLong("id"), row.getLong("task_id"), row.getString("type")),
                limit);
    }

    /**
     * Добавляет событие, ставящее задание в очередь. Вызывается в транзакции, изменившей
     * задание, — событие и изменение фиксируются вместе.
     *
     * @param taskId идентификатор задания
     */
    public void insertTaskEvent(long taskId) {
        jdbcTemplate.update("INSERT INTO outbox_event (task_id) VALUES (?)", taskId);
    }

    /**
     * Отмечает событие опубликованным.
     *
     * @param id идентификатор события
     */
    public void markPublished(long id) {
        jdbcTemplate.update("UPDATE outbox_event SET published_at = now() WHERE id = ?", id);
    }

    /**
     * Увеличивает счётчик неудачных попыток публикации; событие остаётся неопубликованным.
     *
     * @param id идентификатор события
     */
    public void incrementAttempts(long id) {
        jdbcTemplate.update("UPDATE outbox_event SET attempts = attempts + 1 WHERE id = ?", id);
    }
}
