package com.roleorienta.worker.task;

import java.time.Duration;
import java.util.ArrayList;
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
    /** Причина ожидания задания, отложенного из-за занятой группы ({@link #deferQueued}). */
    static final String GROUP_BUSY = "Group busy";
    /** Первое число ключа блокировки группы заданий ({@link #lockGroup(String)}). */
    private static final int GROUP_LOCK_CLASS = 2001;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к общей базе PostgreSQL
     */
    public TaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Создаёт задание в состоянии WAITING со сроком — начало следующего часа по часам БД, если задания с таким ключом
     * ещё нет. В очередь его ставит перепостановка ({@link #claimDue(int)}), когда срок наступит.
     *
     * @param type    тип задания
     * @param taskKey ключ идемпотентности (уникален)
     * @param payload параметры в JSON
     * @return {@code true} — задание создано; {@code false} — задание с этим ключом уже есть
     */
    public boolean insertNextHourIfAbsent(String type, String taskKey, String payload) {
        return jdbcTemplate.update("""
                INSERT INTO task (type, task_key, payload, state, next_attempt_at)
                VALUES (?, ?, ?::jsonb, 'WAITING', date_trunc('hour', now()) + interval '1 hour')
                ON CONFLICT (task_key) DO NOTHING
                """, type, taskKey, payload) > 0;
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
     * Есть ли у типа незавершённое задание (QUEUED, RUNNING, WAITING) с ключом, начинающимся на {@code keyPrefix},
     * менявшееся за последние {@code staleAfter}: цепочка ещё идёт. Задание, не менявшееся дольше, цепочку не держит
     * (потерянное сообщение не должно останавливать шаг навсегда); выполняющееся задание меняется продлением аренды.
     *
     * @param type       тип задания
     * @param keyPrefix  начало ключа цепочки (у типа с несколькими цепочками — шаг); пустое — любое задание типа
     * @param staleAfter задания, не менявшиеся дольше (по часам БД), не учитываются
     * @return {@code true} — цепочка идёт
     */
    public boolean hasActive(String type, String keyPrefix, Duration staleAfter) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM task WHERE type = ? AND left(task_key, length(?)) = ?
                                                    AND state IN ('QUEUED', 'RUNNING', 'WAITING')
                                                    AND updated_at > now() - make_interval(secs => ?))
                """, Boolean.class, type, keyPrefix, keyPrefix, (double) staleAfter.toSeconds()));
    }

    /**
     * Ключи незавершённых заданий типа (QUEUED, RUNNING, WAITING), менявшихся за последние {@code staleAfter} —
     * как {@link #hasActive}: задание без сообщения, не менявшееся дольше, работу не держит (аудит §98).
     *
     * @param type       тип задания
     * @param staleAfter задания, не менявшиеся дольше (по часам БД), не учитываются
     * @return ключи
     */
    public List<String> activeKeys(String type, Duration staleAfter) {
        return jdbcTemplate.queryForList("""
                SELECT task_key FROM task WHERE type = ? AND state IN ('QUEUED', 'RUNNING', 'WAITING')
                                            AND updated_at > now() - make_interval(secs => ?)
                """, String.class, type, (double) staleAfter.toSeconds());
    }

    /**
     * Тип задания.
     *
     * @param taskId идентификатор задания
     * @return тип; пусто — задания нет
     */
    Optional<String> typeOf(long taskId) {
        return jdbcTemplate.queryForList("SELECT type FROM task WHERE id = ?", String.class, taskId).stream()
                .findFirst();
    }

    /**
     * Захватывает задание на выполнение: QUEUED → RUNNING с арендой и следующим номером захвата. Условие на
     * состояние делает захват единственным: повторно доставленное сообщение задание уже не захватит.
     *
     * @param taskId идентификатор задания
     * @param lease  срок аренды
     * @return захваченное задание с номером захвата; пусто, если оно не в состоянии QUEUED
     */
    Optional<ClaimedTask> claim(long taskId, Duration lease) {
        return claim(taskId, lease, "", List.of());
    }

    /**
     * Захват, как {@link #claim(long, Duration)}, но только если ни одно задание из {@code groupTypes} не выполняется
     * (RUNNING с живой арендой): задания одной группы ходят на один внешний хост и параллельно только ждут друг друга
     * (стенограмма §95). И только если нет более старого задания группы, отложенного из-за занятости
     * ({@link #GROUP_BUSY}) и ещё живого (менялось за {@code waitingAlive}): группа достаётся по очереди — продолжение
     * цепочки, поставленное раньше, чем освободилась группа, не обгоняет отложенные задания других цепочек (аудит
     * §95, стенограмма §96). Отложенное задание, не менявшееся дольше {@code waitingAlive} (потерянное сообщение),
     * очередь не держит. Вызывается в транзакции после {@link #lockGroup(String)}, иначе два захвата одновременно
     * увидят группу свободной.
     *
     * @param taskId       идентификатор задания
     * @param lease        срок аренды
     * @param groupTypes   типы группы
     * @param waitingAlive отложенное задание, менявшееся за этот срок, считается ждущим
     * @return захваченное задание; пусто — задание не в QUEUED, группа занята или первым ждёт более старое задание
     */
    Optional<ClaimedTask> claimIfGroupFree(long taskId, Duration lease, List<String> groupTypes,
            Duration waitingAlive) {
        String types = String.join(",", groupTypes);
        return claim(taskId, lease, """
                AND NOT EXISTS (SELECT 1 FROM task busy WHERE busy.type = ANY(string_to_array(?, ','))
                                AND busy.state = 'RUNNING' AND busy.lease_until > now())
                AND NOT EXISTS (SELECT 1 FROM task older WHERE older.type = ANY(string_to_array(?, ','))
                                AND older.id < task.id AND older.state IN ('QUEUED', 'WAITING')
                                AND older.last_error = ?
                                AND older.updated_at > now() - make_interval(secs => ?))""",
                List.of(types, types, GROUP_BUSY, waitingAlive.toSeconds()));
    }

    private Optional<ClaimedTask> claim(long taskId, Duration lease, String condition, List<Object> conditionArgs) {
        List<Object> args = new ArrayList<>(List.of(lease.toSeconds(), taskId));
        args.addAll(conditionArgs);
        List<ClaimedTask> claimed = jdbcTemplate.query("""
                UPDATE task
                SET state = 'RUNNING', lease_until = now() + make_interval(secs => ?), claim_seq = claim_seq + 1,
                    updated_at = now()
                WHERE id = ? AND state = 'QUEUED'
                """ + condition + """

                RETURNING id, type, payload::text AS payload, attempts, claim_seq
                """,
                (row, rowNum) -> new ClaimedTask(new TaskRecord(row.getLong("id"), row.getString("type"),
                        row.getString("payload"), row.getInt("attempts")), row.getInt("claim_seq")),
                args.toArray());
        return claimed.stream().findFirst();
    }

    /**
     * Блокировка группы до конца текущей транзакции ({@code pg_advisory_xact_lock}; ключ из двух чисел — это
     * пространство ключей не пересекается с одночисловыми ключами leader-lock).
     * https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS
     *
     * @param group имя группы
     */
    void lockGroup(String group) {
        jdbcTemplate.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(?, ?)", Integer.class, GROUP_LOCK_CLASS,
                group.hashCode());
    }

    /**
     * QUEUED → WAITING без попытки с причиной {@link #GROUP_BUSY}: группа занята или первым ждёт более старое задание;
     * задание ждёт {@code delay} и снова ставится перепостановкой. Причина сохраняется до захвата — по ней задание
     * держит своё место в очереди группы.
     *
     * @param taskId идентификатор задания
     * @param delay  ожидание
     */
    void deferQueued(long taskId, Duration delay) {
        jdbcTemplate.update("""
                UPDATE task SET state = 'WAITING', next_attempt_at = now() + make_interval(secs => ?), last_error = ?,
                    updated_at = now()
                WHERE id = ? AND state = 'QUEUED'
                """, delay.toSeconds(), GROUP_BUSY, taskId);
    }

    /**
     * Продлевает аренду выполняющегося задания, пока номер захвата прежний.
     *
     * @param taskId   идентификатор задания
     * @param claimSeq номер захвата исполнителя
     * @param lease    новый срок аренды от текущего момента
     * @return {@code false} — задание уже не у этого исполнителя (перепоставлено, захвачено снова или завершено)
     */
    boolean extendLease(long taskId, int claimSeq, Duration lease) {
        return jdbcTemplate.update("""
                UPDATE task SET lease_until = now() + make_interval(secs => ?), updated_at = now()
                WHERE id = ? AND state = 'RUNNING' AND claim_seq = ?
                """, lease.toSeconds(), taskId, claimSeq) == 1;
    }

    /**
     * RUNNING → DONE, если номер захвата прежний.
     *
     * @param taskId   идентификатор задания
     * @param claimSeq номер захвата исполнителя
     * @return {@code false} — задание уже не у этого исполнителя, итог не записан
     */
    boolean markDone(long taskId, int claimSeq) {
        return jdbcTemplate.update("""
                UPDATE task SET state = 'DONE', lease_until = NULL, last_error = NULL, updated_at = now()
                WHERE id = ? AND state = 'RUNNING' AND claim_seq = ?
                """, taskId, claimSeq) == 1;
    }

    /**
     * RUNNING → WAITING: повтор не раньше чем через {@code delay}, если номер захвата прежний.
     *
     * @param taskId   идентификатор задания
     * @param claimSeq номер захвата исполнителя
     * @param attempts новое число неудачных попыток
     * @param delay    интервал до повтора
     * @param error    причина неудачи
     */
    void markWaiting(long taskId, int claimSeq, int attempts, Duration delay, String error) {
        jdbcTemplate.update("""
                UPDATE task
                SET state = 'WAITING', attempts = ?, next_attempt_at = now() + make_interval(secs => ?),
                    lease_until = NULL, last_error = ?, updated_at = now()
                WHERE id = ? AND state = 'RUNNING' AND claim_seq = ?
                """, attempts, delay.toSeconds(), truncate(error), taskId, claimSeq);
    }

    /**
     * RUNNING → FAILED, если номер захвата прежний.
     *
     * @param taskId   идентификатор задания
     * @param claimSeq номер захвата исполнителя
     * @param attempts итоговое число неудачных попыток
     * @param error    причина неудачи
     */
    void markFailed(long taskId, int claimSeq, int attempts, String error) {
        jdbcTemplate.update("""
                UPDATE task SET state = 'FAILED', attempts = ?, lease_until = NULL, last_error = ?,
                    updated_at = now()
                WHERE id = ? AND state = 'RUNNING' AND claim_seq = ?
                """, attempts, truncate(error), taskId, claimSeq);
    }

    /**
     * Захватывает задания, которые пора снова поставить в очередь: WAITING с наступившим сроком
     * повтора и RUNNING с истёкшей арендой. {@code FOR UPDATE SKIP LOCKED} — строки заблокированы
     * до конца транзакции. Вызывается внутри транзакции.
     *
     * @param limit максимум заданий
     * @return задания с признаком «истекла аренда»
     */
    List<DueTask> claimDue(int limit) {
        return jdbcTemplate.query("""
                SELECT id, state = 'RUNNING' AS lease_expired FROM task
                WHERE (state = 'WAITING' AND next_attempt_at <= now())
                   OR (state = 'RUNNING' AND lease_until < now())
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (row, rowNum) -> new DueTask(row.getLong("id"), row.getBoolean("lease_expired")), limit);
    }

    /**
     * Истекла аренда (исполнитель пропал или завис): это неудачная попытка — RUNNING → QUEUED, а на последней
     * допустимой попытке → FAILED. Без счёта попыток задание, которое каждый раз не укладывается в аренду,
     * перепоставлялось бы без конца (стенограмма §94).
     *
     * @param taskId      идентификатор задания
     * @param maxAttempts допустимое число неудачных попыток
     * @return {@code true} — задание снова в очереди; {@code false} — попытки исчерпаны (FAILED)
     */
    boolean expireLease(long taskId, int maxAttempts) {
        return jdbcTemplate.queryForObject("""
                UPDATE task SET attempts = attempts + 1,
                    state = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'QUEUED' END,
                    lease_until = NULL, next_attempt_at = NULL, last_error = 'Lease expired', updated_at = now()
                WHERE id = ?
                RETURNING state = 'QUEUED'
                """, Boolean.class, maxAttempts, taskId);
    }

    /**
     * Захват не удался из-за ошибки (не недоступности БД): неудачная попытка — QUEUED → WAITING с повтором через
     * {@code delay}, на последней допустимой попытке → FAILED. Без этого задание осталось бы QUEUED без сообщения
     * (сообщение — в DLQ) и держало бы цепочку и чтение источника (аудит §95).
     *
     * @param taskId      идентификатор задания
     * @param maxAttempts допустимое число неудачных попыток
     * @param delay       интервал до повтора
     * @param error       причина
     */
    void failClaim(long taskId, int maxAttempts, Duration delay, String error) {
        jdbcTemplate.update("""
                UPDATE task SET attempts = attempts + 1,
                    state = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'WAITING' END,
                    next_attempt_at = now() + make_interval(secs => ?), last_error = ?, updated_at = now()
                WHERE id = ? AND state = 'QUEUED'
                """, maxAttempts, delay.toSeconds(), truncate(error), taskId);
    }

    /**
     * WAITING → QUEUED.
     *
     * @param taskId идентификатор задания
     */
    void markQueued(long taskId) {
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
