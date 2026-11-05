package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.task.TaskService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ставит чтение позиций profesia.sk накопленных объявлений ({@link ProfesiaPositionsHandler}, §267) под leader-lock:
 * тик раз в час (первый — при запуске воркера), ключ задания по часу — одно задание в час. Выключается вместе с
 * работой с государственным порталом ({@code app.state-portal.enabled=false}).
 */
@Component
@ConditionalOnProperty(name = "app.state-portal.enabled", havingValue = "true", matchIfMissing = true)
public class ProfesiaPositionsTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1024;

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");
    private static final Logger LOG = LoggerFactory.getLogger(ProfesiaPositionsTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param clock       часы
     */
    public ProfesiaPositionsTick(PostgresLeaderLock leaderLock, TaskService taskService, Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * Тик: поставить чтение позиций этого часа (повтор в тот же час ничего не добавляет).
     */
    @Scheduled(fixedDelayString = "${app.state-portal.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.enqueue(ProfesiaPositionsHandler.TYPE,
                    "profesia-positions:" + LocalDateTime.now(clock).format(HOUR), "{}"));
        } catch (DataAccessException exception) {
            LOG.warn("Profesia positions tick failed, will retry on next tick", exception);
        }
    }
}
