package com.roleorienta.worker.task;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодическая перепостановка заданий ({@link TaskService#requeueDue()}) под leader-lock.
 * Выключается свойством {@code app.task.requeue-enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.task.requeue-enabled", havingValue = "true", matchIfMissing = true)
public class TaskRequeueTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1001;

    private static final Logger LOG = LoggerFactory.getLogger(TaskRequeueTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     */
    public TaskRequeueTick(PostgresLeaderLock leaderLock, TaskService taskService) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
    }

    /**
     * Тик перепостановки. Ошибка БД не прерывает расписание — повтор на следующем тике.
     */
    @Scheduled(fixedDelayString = "${app.task.requeue-interval-ms:10000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, taskService::requeueDue);
        } catch (DataAccessException exception) {
            LOG.warn("Task requeue tick failed, will retry on next tick", exception);
        }
    }
}
