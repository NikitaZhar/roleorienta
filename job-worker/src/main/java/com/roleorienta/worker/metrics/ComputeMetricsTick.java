package com.roleorienta.worker.metrics;

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
 * Ставит расчёт показателей ядра за вчерашний день ({@link ComputeMetricsHandler}, §131) под leader-lock: тик раз в
 * час, ключ задания по дню — день считается один раз, вскоре после своего конца (в часовом поясе показателей).
 * Выключается свойством {@code app.metrics.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class ComputeMetricsTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1017;

    private static final Logger LOG = LoggerFactory.getLogger(ComputeMetricsTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final MetricsProperties properties;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param properties  часовой пояс суток
     * @param clock       часы
     */
    public ComputeMetricsTick(PostgresLeaderLock leaderLock, TaskService taskService, MetricsProperties properties,
            Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Тик: поставить расчёт за вчера (повторная постановка того же дня ничего не делает).
     */
    @Scheduled(fixedDelayString = "${app.metrics.interval-ms:3600000}")
    public void tick() {
        LocalDate yesterday = LocalDate.now(clock.withZone(properties.zone())).minusDays(1);
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.enqueue(ComputeMetricsHandler.TYPE,
                    "metrics:" + yesterday, ComputeMetricsHandler.payload(yesterday)));
        } catch (DataAccessException exception) {
            LOG.warn("Core metrics tick failed, will retry on next tick", exception);
        }
    }
}
