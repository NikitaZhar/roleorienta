package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Под leader-lock раз в час ставит ленту «новые сверху» общего списка портала и начинает полный обход, когда пора
 * (стенограмма §105). Выключается вместе с остальной работой с порталом: {@code app.state-portal.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.state-portal.enabled", havingValue = "true", matchIfMissing = true)
public class PortalListTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1014;

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");
    private static final Logger LOG = LoggerFactory.getLogger(PortalListTick.class);

    private final PostgresLeaderLock leaderLock;
    private final PortalListHandler handler;
    private final Clock clock;

    /**
     * @param leaderLock leader-lock
     * @param handler    постановка ленты и полного обхода
     * @param clock      часы
     */
    public PortalListTick(PostgresLeaderLock leaderLock, PortalListHandler handler, Clock clock) {
        this.leaderLock = leaderLock;
        this.handler = handler;
        this.clock = clock;
    }

    /**
     * Тик постановки. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.state-portal.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> {
                handler.enqueueFeed(LocalDateTime.now(clock).format(HOUR));
                handler.startCycleIfDue();
            });
        } catch (DataAccessException exception) {
            LOG.warn("State portal list tick failed, will retry on next tick", exception);
        }
    }
}
