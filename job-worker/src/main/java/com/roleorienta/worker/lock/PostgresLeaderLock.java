package com.roleorienta.worker.lock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leader-lock поверх advisory-локов PostgreSQL: гарантирует, что защищённую
 * работу в каждый момент выполняет только одна реплика worker (ADR-12).
 *
 * <p>Используется {@code pg_try_advisory_xact_lock(key)} — <b>транзакционный</b>
 * advisory-лок: он привязан к текущей транзакции и автоматически освобождается
 * при её завершении. Это осознанный выбор против сессионного
 * {@code pg_advisory_lock}: в пуле соединений «взять» и «отпустить» сессионный
 * лок нужно на одном и том же соединении, что легко нарушить; транзакционный
 * лок этой ловушки лишён и не требует ручного освобождения (нет утечки лока при
 * ошибке). Метод помечен {@link Transactional}, поэтому лок и задача выполняются
 * в одной транзакции (на одном соединении).
 * Документация:
 * https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS</p>
 *
 * <p>Ограничение (A17): это защита от одновременного захвата, а не «пожизненная»
 * единственность. Поэтому она остаётся дополнением к идемпотентности планирования
 * (уникальный ключ на окно расписания), а не заменой ей. К живому тику
 * планировщика компонент подключается в инкременте планировщика заданий.</p>
 */
@Component
public class PostgresLeaderLock {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД для вызова функции блокировки
     */
    public PostgresLeaderLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Пытается захватить лок с заданным ключом и, при успехе, выполнить задачу.
     * Лок не удерживается в ожидании: если его держит другая реплика, метод
     * немедленно возвращает {@code false}, ничего не выполняя. Лок освобождается
     * автоматически при завершении транзакции метода.
     *
     * @param lockKey ключ лока (согласованное число для конкретной роли, например планировщика)
     * @param task    задача, выполняемая только лидером
     * @return {@code true}, если лок захвачен и задача выполнена; {@code false} — иначе
     */
    @Transactional
    public boolean runIfLeader(long lockKey, Runnable task) {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKey);
        if (Boolean.TRUE.equals(acquired)) {
            task.run();
            return true;
        }
        return false;
    }
}
