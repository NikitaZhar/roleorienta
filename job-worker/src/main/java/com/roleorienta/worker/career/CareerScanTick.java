package com.roleorienta.worker.career;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.task.TaskService;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Раз в сутки ставит проверку сайтов компаний под leader-lock; задание берёт непроверенные и
 * просроченные сайты цепочкой заданий. Выключается свойством {@code app.career.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.career.enabled", havingValue = "true", matchIfMissing = true)
public class CareerScanTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1005;

    private static final Logger LOG = LoggerFactory.getLogger(CareerScanTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param clock       часы
     */
    public CareerScanTick(PostgresLeaderLock leaderLock, TaskService taskService, Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * Тик постановки проверки. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.career.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.enqueue(CareerScanHandler.TYPE,
                    CareerScanHandler.taskKey(LocalDate.now(clock).toString()), CareerScanHandler.payload()));
        } catch (DataAccessException exception) {
            LOG.warn("Career scan tick failed, will retry on next tick", exception);
        }
    }
}
