package com.roleorienta.worker.snapshot;

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
 * Ставит удаление снимков по сроку ({@link SnapshotPurgeHandler}, §130) под leader-lock: тик раз в час, ключ задания
 * по дате — удаление раз в сутки, повторная постановка в тот же день ничего не делает. Выключается свойством
 * {@code app.snapshot.purge.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.snapshot.purge.enabled", havingValue = "true", matchIfMissing = true)
public class SnapshotPurgeTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1016;

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotPurgeTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param clock       часы — дата в ключе задания
     */
    public SnapshotPurgeTick(PostgresLeaderLock leaderLock, TaskService taskService, Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * Тик: поставить удаление снимков на сегодня.
     */
    @Scheduled(fixedDelayString = "${app.snapshot.purge.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.enqueue(SnapshotPurgeHandler.TYPE,
                    "snapshot-purge:" + LocalDate.now(clock), "{}"));
        } catch (DataAccessException exception) {
            LOG.warn("Snapshot purge tick failed, will retry on next tick", exception);
        }
    }
}
