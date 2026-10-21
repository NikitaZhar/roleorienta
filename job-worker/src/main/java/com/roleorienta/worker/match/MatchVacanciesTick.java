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
 * Раз в час и при запуске воркера ставит предрасчёт соответствий под leader-lock: новые и изменившиеся за час вакансии
 * сопоставляются; пока цепочка идёт, новая не ставится (стенограмма §95). Ключ первого задания — час и версия
 * сопоставления ({@link MatchVacanciesHandler#currentVersion()}): после выкладки с новой версией (словарь, регионы,
 * правила мест, слова языков) пересчёт начинается сразу при запуске, а не в следующем часу — ключ этого часа со старой
 * версией уже занят (§158, §167). Смена словаря администратором — со следующего такта. Выключается свойством
 * {@code app.match.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.match.enabled", havingValue = "true", matchIfMissing = true)
public class MatchVacanciesTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1007;

    private static final Logger LOG = LoggerFactory.getLogger(MatchVacanciesTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final MatchVacanciesHandler handler;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param handler     текущая версия сопоставления
     * @param clock       часы
     */
    public MatchVacanciesTick(PostgresLeaderLock leaderLock, TaskService taskService, MatchVacanciesHandler handler,
            Clock clock) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.handler = handler;
        this.clock = clock;
    }

    /**
     * @param hour    час постановки
     * @param version версия сопоставления
     * @return ключ первого задания цепочки
     */
    static String firstTaskKey(String hour, String version) {
        return MatchVacanciesHandler.taskKey(hour + ":" + version);
    }

    /**
     * Тик постановки. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.match.interval-ms:3600000}")
    public void tick() {
        String hour = LocalDateTime.now(clock).truncatedTo(ChronoUnit.HOURS).toString();
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> taskService.startChain(MatchVacanciesHandler.TYPE,
                    firstTaskKey(hour, handler.currentVersion()), MatchVacanciesHandler.payload()));
        } catch (DataAccessException exception) {
            LOG.warn("Match tick failed, will retry on next tick", exception);
        }
    }
}
