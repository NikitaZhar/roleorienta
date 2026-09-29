package com.roleorienta.worker.intake;

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
 * Раз в сутки ставит задание приёма реестра (ключ по дате — повторная постановка в тот же день ничего
 * не добавляет) под leader-lock. Выключается свойством {@code app.intake.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.intake.enabled", havingValue = "true", matchIfMissing = true)
public class RegistryIntakeTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1003;

    private static final Logger LOG = LoggerFactory.getLogger(RegistryIntakeTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param clock       часы
     */
    public RegistryIntakeTick(PostgresLeaderLock leaderLock, TaskService taskService, Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * Тик постановки приёма. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.intake.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.enqueue(RegistryIntakeHandler.TYPE,
                    RegistryIntakeHandler.taskKey(LocalDate.now(clock).toString()), RegistryIntakeHandler.payload()));
        } catch (DataAccessException exception) {
            LOG.warn("Registry intake tick failed, will retry on next tick", exception);
        }
    }
}
