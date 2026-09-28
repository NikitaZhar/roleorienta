package com.roleorienta.worker.task;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Доступ к таблице {@code task}. Только запросы, без логики. Время берётся из БД ({@code now()}),
 * сроки передаются длительностью в секундах.
 */
@Repository
public class TaskRepository {

    private static final int ERROR_MAX_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к общей базе PostgreSQL
     */
    public TaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Создаёт задание в состоянии QUEUED, если задания с таким ключом ещё нет.
     *
     * @param type    тип задания
     * @param taskKey ключ идемпотентности (уникален)
     * @param payload параметры в JSON
     * @return id нового задания; пусто, если задание с этим ключом уже есть
     */
    public Optional<Long> insertIfAbsent(String type, String taskKey, String payload) {
        List<Long> ids = jdbcTemplate.queryForList("""
                INSERT INTO task (type, task_key, payload, state)
                VALUES (?, ?, ?::jsonb, 'QUEUED')
                ON CONFLICT (task_key) DO NOTHING
                RETURNING id
                """, Long.class, type, taskKey, payload);
        return ids.stream().findFirst();
    }

    /**
     * Захватывает задание на выполнение: QUEUED → RUNNING с арендой. Условие на состояние делает
     * захват единственным: повторно доставленное сообщение задание уже не захватит.
     *
     * @param taskId идентификатор задания
     * @param lease  срок аренды
     * @return захваченное задание; пусто, если оно не в состоянии QUEUED
     */
    public Optional<TaskRecord> claim(long taskId, Duration lease) {
        List<TaskRecord> claimed = jdbcTemplate.query("""
                UPDATE task
                SET state = 'RUNNING', lease_until = now() + make_interval(secs => ?), updated_at = now()
                WHERE id = ? AND state = 'QUEUED'
                RETURNING id, type, payload::text AS payload, attempts
                """,
                (row, rowNum) -> new TaskRecord(row.getLong("id"), row.getString("type"),
                        row.getString("payload"), row.getInt("attempts")),
                lease.toSeconds(), taskId);
        return claimed.stream().findFirst();
    }

    /**
     * RUNNING → DONE.
     *
     * @param taskId идентификатор задания
     */
    public void markDone(long taskId) {
        jdbcTemplate.update("""
                UPDATE task SET state = 'DONE', lease_until = NULL, last_error = NULL, updated_at = now()
                WHERE id = ? AND state = 'RUNNING'
                """, taskId);
    }

    /**
     * RUNNING → WAITING: повтор не раньше чем через {@code delay}.
     *
     * @param taskId   идентификатор задания
     * @param attempts новое число неудачных попыток
     * @param delay    интервал до повтора
     * @param error    причина неудачи
     */
    public void markWaiting(long taskId, int attempts, Duration delay, String error) {
        jdbcTemplate.update("""
                UPDATE task
                SET state = 'WAITING', attempts = ?, next_attempt_at = now() + make_interval(secs => ?),
                    lease_until = NULL, last_error = ?, updated_at = now()
                WHERE id = ? AND state = 'RUNNING'
                """, attempts, delay.toSeconds(), truncate(error), taskId);
    }

    /**
     * RUNNING → FAILED.
     *
     * @param taskId   идентификатор задания
     * @param attempts итоговое число неудачных попыток
     * @param error    причина неудачи
     */
    public void markFailed(long taskId, int attempts, String error) {
        jdbcTemplate.update("""
                UPDATE task SET state = 'FAILED', attempts = ?, lease_until = NULL, last_error = ?,
                    updated_at = now()
                WHERE id = ? AND state = 'RUNNING'
                """, attempts, truncate(error), taskId);
    }

    /**
     * Захватывает задания, которые пора снова поставить в очередь: WAITING с наступившим сроком
     * повтора и RUNNING с истёкшей арендой. {@code FOR UPDATE SKIP LOCKED} — строки заблокированы
     * до конца транзакции. Вызывается внутри транзакции.
     *
     * @param limit максимум заданий
     * @return идентификаторы заданий
     */
    public List<Long> claimDue(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT id FROM task
                WHERE (state = 'WAITING' AND next_attempt_at <= now())
                   OR (state = 'RUNNING' AND lease_until < now())
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, Long.class, limit);
    }

    /**
     * → QUEUED.
     *
     * @param taskId идентификатор задания
     */
    public void markQueued(long taskId) {
        jdbcTemplate.update("""
                UPDATE task SET state = 'QUEUED', next_attempt_at = NULL, lease_until = NULL, updated_at = now()
                WHERE id = ?
                """, taskId);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= ERROR_MAX_LENGTH) {
            return error;
        }
        return error.substring(0, ERROR_MAX_LENGTH);
    }
}
