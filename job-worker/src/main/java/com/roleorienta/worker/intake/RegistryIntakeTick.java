package com.roleorienta.worker.intake;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Тик общего сбора под leader-lock: страны сбора обновляются по условиям пользователей, ставится
 * партия первой в очереди страны ({@link CollectionPlanner}); дальше партии идут цепочкой. Выключается
 * свойством {@code app.intake.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.intake.enabled", havingValue = "true", matchIfMissing = true)
public class RegistryIntakeTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1003;

    private static final Logger LOG = LoggerFactory.getLogger(RegistryIntakeTick.class);

    private final PostgresLeaderLock leaderLock;
    private final CollectionPlanner planner;

    /**
     * @param leaderLock leader-lock
     * @param planner    очередь стран сбора
     */
    public RegistryIntakeTick(PostgresLeaderLock leaderLock, CollectionPlanner planner) {
        this.leaderLock = leaderLock;
        this.planner = planner;
    }

    /**
     * Тик постановки приёма. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.intake.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, planner::refreshAndEnqueue);
        } catch (DataAccessException exception) {
            LOG.warn("Registry intake tick failed, will retry on next tick", exception);
        }
    }
}
