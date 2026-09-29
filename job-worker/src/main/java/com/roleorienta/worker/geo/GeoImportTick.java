package com.roleorienta.worker.geo;

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
 * Ставит загрузку справочника GeoNames под leader-lock, пока справочник пуст (первый запуск).
 * Выключается свойством {@code app.geo.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.geo.enabled", havingValue = "true", matchIfMissing = true)
public class GeoImportTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1008;

    private static final Logger LOG = LoggerFactory.getLogger(GeoImportTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final GeoRepository repository;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param repository  справочник городов
     * @param clock       часы
     */
    public GeoImportTick(PostgresLeaderLock leaderLock, TaskService taskService, GeoRepository repository,
            Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Тик: справочник пуст — поставить загрузку (ключ по дате: неудачная загрузка повторится завтра).
     */
    @Scheduled(fixedDelayString = "${app.geo.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> {
                if (!repository.isLoaded()) {
                    taskService.enqueue(GeoImportHandler.TYPE, "geo-import:" + LocalDate.now(clock), "{}");
                }
            });
        } catch (DataAccessException exception) {
            LOG.warn("Geo import tick failed, will retry on next tick", exception);
        }
    }
}
