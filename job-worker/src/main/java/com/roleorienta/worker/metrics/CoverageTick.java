package com.roleorienta.worker.metrics;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Пересчёт полноты сбора по компаниям ({@code company_coverage}, §234) раз в час и при запуске job-worker (§238):
 * очередь потерь и уведомления администратору (§235) обновляются в течение часа, а не раз в сутки с показателями дня.
 * Под leader-lock: пересчитывает одна реплика; сбой — повтор следующим тиком. Выключается вместе с показателями
 * ({@code app.metrics.enabled=false}).
 */
@Component
@ConditionalOnProperty(name = "app.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class CoverageTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1021;

    private static final Logger LOG = LoggerFactory.getLogger(CoverageTick.class);

    private final PostgresLeaderLock leaderLock;
    private final MetricsRepository metrics;

    /**
     * @param leaderLock leader-lock
     * @param metrics    полнота сбора
     */
    public CoverageTick(PostgresLeaderLock leaderLock, MetricsRepository metrics) {
        this.leaderLock = leaderLock;
        this.metrics = metrics;
    }

    /**
     * Тик: первый — сразу после запуска, затем через {@code app.metrics.coverage-interval-ms} после окончания прошлого.
     */
    @Scheduled(fixedDelayString = "${app.metrics.coverage-interval-ms:3600000}")
    public void tick() {
        try {
            if (leaderLock.runIfLeader(LOCK_KEY, metrics::refreshCoverage)) {
                LOG.info("Collection coverage by company refreshed");
            }
        } catch (DataAccessException exception) {
            LOG.warn("Collection coverage refresh failed, will retry on next tick", exception);
        }
    }
}
