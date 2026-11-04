package com.roleorienta.worker.occupation;

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
 * Ставит распознавание позиции моделью ({@link TitleClassificationHandler}, §262) под leader-lock: тик раз в час, ключ
 * задания по часу — одно задание в час. Включается свойством {@code app.title-classification.enabled=true}
 * ({@code TITLE_CLASSIFICATION_ENABLED}).
 */
@Component
@ConditionalOnProperty(name = "app.title-classification.enabled", havingValue = "true")
public class TitleClassificationTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1022;

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");
    private static final Logger LOG = LoggerFactory.getLogger(TitleClassificationTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param clock       часы
     */
    public TitleClassificationTick(PostgresLeaderLock leaderLock, TaskService taskService, Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * Тик: поставить распознавание этого часа (повторная постановка того же часа ничего не делает).
     */
    @Scheduled(fixedDelayString = "${app.title-classification.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.enqueue(TitleClassificationHandler.TYPE,
                    "title-classify:" + LocalDateTime.now(clock).format(HOUR), "{}"));
        } catch (DataAccessException exception) {
            LOG.warn("Title classification tick failed, will retry on next tick", exception);
        }
    }
}
