package com.roleorienta.worker.site;

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
 * Раз в сутки ставит скан Common Crawl под leader-lock: новый обход (раз в месяц) сканируется, уже
 * просканированный — задание сразу завершается. Выключается свойством {@code app.site.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.site.enabled", havingValue = "true", matchIfMissing = true)
public class SiteScanTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1004;

    private static final Logger LOG = LoggerFactory.getLogger(SiteScanTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param clock       часы
     */
    public SiteScanTick(PostgresLeaderLock leaderLock, TaskService taskService, Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * Тик постановки скана. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.site.interval-ms:3600000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.enqueue(SiteScanHandler.TYPE,
                    SiteScanHandler.taskKey(LocalDate.now(clock).toString()), SiteScanHandler.payload()));
        } catch (DataAccessException exception) {
            LOG.warn("Site scan tick failed, will retry on next tick", exception);
        }
    }
}
