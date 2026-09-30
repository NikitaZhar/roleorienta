package com.roleorienta.worker.delivery;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Раз в час планирует проход текущего окна (начало часа) под leader-lock (технический документ §7,
 * §17). Выключается свойством {@code app.delivery.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class PassTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1009;

    private static final Logger LOG = LoggerFactory.getLogger(PassTick.class);

    private final PostgresLeaderLock leaderLock;
    private final PassPlanner planner;
    private final Clock clock;

    /**
     * @param leaderLock leader-lock
     * @param planner    планирование прохода
     * @param clock      часы
     */
    public PassTick(PostgresLeaderLock leaderLock, PassPlanner planner, Clock clock) {
        this.leaderLock = leaderLock;
        this.planner = planner;
        this.clock = clock;
    }

    /**
     * Тик планирования. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.delivery.interval-ms:3600000}")
    public void tick() {
        Instant window = Instant.now(clock).truncatedTo(ChronoUnit.HOURS);
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> planner.plan(window));
        } catch (DataAccessException exception) {
            LOG.warn("Pass tick failed, will retry on next tick", exception);
        }
    }
}
