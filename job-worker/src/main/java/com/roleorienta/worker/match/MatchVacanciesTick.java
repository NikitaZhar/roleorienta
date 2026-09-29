package com.roleorienta.worker.match;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.task.TaskService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Раз в час ставит предрасчёт соответствий под leader-lock (ключ по часу): новые и изменившиеся за
 * час вакансии сопоставляются. Выключается свойством {@code app.match.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.match.enabled", havingValue = "true", matchIfMissing = true)
public class MatchVacanciesTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1007;

    private static final Logger LOG = LoggerFactory.getLogger(MatchVacanciesTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param clock       часы
     */
    public MatchVacanciesTick(PostgresLeaderLock leaderLock, TaskService taskService, Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * Тик постановки. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.match.interval-ms:3600000}")
    public void tick() {
        String hour = LocalDateTime.now(clock).truncatedTo(ChronoUnit.HOURS).toString();
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.enqueue(MatchVacanciesHandler.TYPE,
                    MatchVacanciesHandler.taskKey(hour), MatchVacanciesHandler.payload()));
        } catch (DataAccessException exception) {
            LOG.warn("Match tick failed, will retry on next tick", exception);
        }
    }
}
