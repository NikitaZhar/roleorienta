package com.roleorienta.worker.metrics;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.task.TaskService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ставит расчёт показателей ядра ({@link ComputeMetricsHandler}, §131) под leader-lock: тик раз в час, за каждый из
 * последних {@value #BACKFILL_DAYS} прошедших дней без показателей — день, пропущенный из-за остановки воркера,
 * досчитывается (аудит §133). Ключ задания по дню — день ставится один раз; задание, не выполненное после всех
 * попыток, повторно не ставится (видно в {@code operations_failed}). Выключается свойством
 * {@code app.metrics.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class ComputeMetricsTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1017;

    /** Сколько прошедших дней проверяется на пропуск. */
    static final int BACKFILL_DAYS = 7;

    private static final Logger LOG = LoggerFactory.getLogger(ComputeMetricsTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final MetricsRepository metrics;
    private final MetricsProperties properties;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param metrics     показатели ядра — уже посчитанные дни
     * @param properties  часовой пояс суток
     * @param clock       часы
     */
    public ComputeMetricsTick(PostgresLeaderLock leaderLock, TaskService taskService, MetricsRepository metrics,
            MetricsProperties properties, Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Тик: поставить расчёт прошедших дней без показателей (повторная постановка того же дня ничего не делает).
     */
    @Scheduled(fixedDelayString = "${app.metrics.interval-ms:3600000}")
    public void tick() {
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        LocalDate first = today.minusDays(BACKFILL_DAYS);
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> {
                Set<LocalDate> computed = metrics.computedDays(first);
                for (LocalDate day = first; day.isBefore(today); day = day.plusDays(1)) {
                    if (!computed.contains(day)) {
                        taskService.enqueue(ComputeMetricsHandler.TYPE, "metrics:" + day,
                                ComputeMetricsHandler.payload(day));
                    }
                }
            });
        } catch (DataAccessException exception) {
            LOG.warn("Core metrics tick failed, will retry on next tick", exception);
        }
    }
}
