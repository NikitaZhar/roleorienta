package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Под leader-lock ставит работу с государственным порталом: список работодателей — раз в неделю
 * (ключ — неделя ISO, например {@code 2026-W40}), проверку работодателей — раз в день. Повторный тик в
 * той же неделе или дне ничего не добавляет. Выключается свойством {@code app.state-portal.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.state-portal.enabled", havingValue = "true", matchIfMissing = true)
public class StatePortalTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1010;

    private static final Logger LOG = LoggerFactory.getLogger(StatePortalTick.class);

    private final PostgresLeaderLock leaderLock;
    private final StatePortalHandler handler;
    private final Clock clock;

    /**
     * @param leaderLock leader-lock
     * @param handler    постановка шагов
     * @param clock      часы
     */
    public StatePortalTick(PostgresLeaderLock leaderLock, StatePortalHandler handler, Clock clock) {
        this.leaderLock = leaderLock;
        this.handler = handler;
        this.clock = clock;
    }

    /**
     * Тик постановки. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.state-portal.interval-ms:3600000}")
    public void tick() {
        LocalDate today = LocalDate.now(clock);
        String week = today.get(IsoFields.WEEK_BASED_YEAR) + "-W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> {
                handler.enqueueList(week);
                handler.enqueueCheck(today.toString());
            });
        } catch (DataAccessException exception) {
            LOG.warn("State portal tick failed, will retry on next tick", exception);
        }
    }
}
