package com.roleorienta.worker.intake;

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
 * Раз в сутки ставит шаг «число сотрудников из RÚZ» под leader-lock (ключ задания — день; повторный
 * тик в тот же день ничего не добавляет). Выключается свойством {@code app.company-size.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.company-size.enabled", havingValue = "true", matchIfMissing = true)
public class CompanySizeTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1013;

    private static final Logger LOG = LoggerFactory.getLogger(CompanySizeTick.class);

    private final PostgresLeaderLock leaderLock;
    private final CompanySizeHandler handler;
    private final Clock clock;

    /**
     * @param leaderLock leader-lock
     * @param handler    постановка задания
     * @param clock      часы
     */
    public CompanySizeTick(PostgresLeaderLock leaderLock, CompanySizeHandler handler, Clock clock) {
        this.leaderLock = leaderLock;
        this.handler = handler;
        this.clock = clock;
    }

    /**
     * Тик постановки. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.company-size.interval-ms:3600000}")
    public void tick() {
        String today = LocalDate.now(clock).toString();
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> handler.enqueue(today));
        } catch (DataAccessException exception) {
            LOG.warn("Company size tick failed, will retry on next tick", exception);
        }
    }
}
