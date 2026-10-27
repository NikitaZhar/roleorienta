package com.roleorienta.worker.occupation;

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
 * Ставит пересчёт профессии по названию ({@link TitleOccupationHandler}, §215) под leader-lock: тик раз в час, ключ
 * задания по дате — пересчёт раз в сутки (первый — в первый час после запуска). Выключается свойством
 * {@code app.title-occupations.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.title-occupations.enabled", havingValue = "true", matchIfMissing = true)
public class TitleOccupationTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1020;

    private static final Logger LOG = LoggerFactory.getLogger(TitleOccupationTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param clock       часы
     */
    public TitleOccupationTick(PostgresLeaderLock leaderLock, TaskService taskService, Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * Тик: поставить пересчёт сегодняшнего дня (повторная постановка того же дня ничего не делает).
     */
    @Scheduled(fixedDelayString = "${app.title-occupations.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.enqueue(TitleOccupationHandler.TYPE,
                    "title-occupations:" + LocalDate.now(clock), "{}"));
        } catch (DataAccessException exception) {
            LOG.warn("Title occupations tick failed, will retry on next tick", exception);
        }
    }
}
