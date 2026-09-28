package com.roleorienta.worker.outbox;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Доступ к таблице {@code outbox_event}. Только запросы, без логики.
 */
@Repository
public class OutboxRepository {

    /** Тип события «задание поставлено в очередь». */
    public static final String TASK_EVENT_TYPE = "TASK";

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
                SELECT id, event_type, payload::text AS payload, task_id
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (row, rowNum) -> new OutboxEvent(row.getLong("id"), row.getString("event_type"),
                        row.getString("payload"), row.getObject("task_id", Long.class)),
                limit);
    }

    /**
     * Добавляет событие, ставящее задание в очередь. Вызывается в транзакции, изменившей
     * задание, — событие и изменение фиксируются вместе.
     *
     * @param taskId идентификатор задания
     */
    public void insertTaskEvent(long taskId) {
        jdbcTemplate.update("""
                INSERT INTO outbox_event (event_type, payload, task_id)
                VALUES (?, jsonb_build_object('taskId', ?::bigint), ?)
                """, TASK_EVENT_TYPE, taskId, taskId);
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
