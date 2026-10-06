package com.roleorienta.worker.site;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Раз в сутки ставит шаги 3–4 поиска сайта (адрес по названию) под leader-lock (ключ задания — день; повторный
 * тик в тот же день ничего не добавляет). Выключается свойством {@code app.site-name.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.site-name.enabled", havingValue = "true", matchIfMissing = true)
public class SiteNameTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1012;

    private static final Logger LOG = LoggerFactory.getLogger(SiteNameTick.class);

    private final PostgresLeaderLock leaderLock;
    private final SiteNameHandler handler;
    private final Clock clock;

    /**
     * @param leaderLock leader-lock
     * @param handler    постановка задания
     * @param clock      часы
     */
    public SiteNameTick(PostgresLeaderLock leaderLock, SiteNameHandler handler, Clock clock) {
        this.leaderLock = leaderLock;
        this.handler = handler;
        this.clock = clock;
    }

    /**
     * Тик постановки. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.site-name.interval-ms:3600000}")
    public void tick() {
        String today = LocalDate.now(clock).toString();
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> handler.enqueue(today));
        } catch (DataAccessException exception) {
            LOG.warn("Site by name tick failed, will retry on next tick", exception);
        }
    }
}
