package com.roleorienta.worker.collect;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодический запуск {@link ReadSourcePlanner#enqueueToday()} под leader-lock. Выключается
 * свойством {@code app.collect.read-enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.collect.read-enabled", havingValue = "true", matchIfMissing = true)
public class ReadSourceTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1002;

    private static final Logger LOG = LoggerFactory.getLogger(ReadSourceTick.class);

    private final PostgresLeaderLock leaderLock;
    private final ReadSourcePlanner planner;

    /**
     * @param leaderLock leader-lock
     * @param planner    постановка чтений
     */
    public ReadSourceTick(PostgresLeaderLock leaderLock, ReadSourcePlanner planner) {
        this.leaderLock = leaderLock;
        this.planner = planner;
    }

    /**
     * Тик постановки чтений. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.collect.read-interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, planner::enqueueToday);
        } catch (DataAccessException exception) {
            LOG.warn("Read source tick failed, will retry on next tick", exception);
        }
    }
}
