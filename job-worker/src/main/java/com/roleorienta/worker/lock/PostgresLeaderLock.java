package com.roleorienta.worker.lock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leader-lock на advisory-блокировке PostgreSQL: защищённую работу в каждый момент выполняет одна
 * реплика worker (технический документ §16.4).
 *
 * <p>{@code pg_try_advisory_xact_lock} — транзакционная блокировка: не ждёт, если занята, и сама
 * освобождается при завершении транзакции, поэтому не может «зависнуть» после ошибки. Метод
 * транзакционный — блокировка и работа идут в одной транзакции.
 * https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS</p>
 *
 * <p>Это защита от одновременного выполнения, а не гарантия единственности навсегда; последняя
 * защита от дублей — идемпотентность и уникальные ключи.</p>
 *
 * <p><b>Реестр ключей</b> (новый ключ — следующий номер, сюда же): 1001 — перепостановка заданий
 * ({@code TaskRequeueTick}).</p>
 */
@Component
public class PostgresLeaderLock {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public PostgresLeaderLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Выполняет работу, если блокировка с этим ключом свободна.
     *
     * @param lockKey ключ блокировки роли (реестр — в описании класса)
     * @param work    работа; выполняется в транзакции этого метода
     * @return {@code true} — блокировка захвачена и работа выполнена; {@code false} — занята
     */
    @Transactional
    public boolean runIfLeader(long lockKey, Runnable work) {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKey);
        if (Boolean.TRUE.equals(acquired)) {
            work.run();
            return true;
        }
        return false;
    }
}
