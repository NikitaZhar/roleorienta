package com.roleorienta.worker.digest;

import com.roleorienta.worker.digest.DigestRepository.Due;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.task.TaskService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ставит сводки на почту под leader-lock каждые 5 минут ({@code app.digest.interval-ms}; стенограмма §164): тем, у
 * кого по их часам наступило время письма (8:00) и сегодняшний день подходит под частоту, — задание
 * {@code SEND_DIGEST} с ключом «пользователь + день» (повтор такта второго задания не ставит). Включается
 * {@code app.digest.enabled=true} ({@code DIGEST_ENABLED}): без настроенной почты стенд работает без сводок.
 */
@Component
@ConditionalOnProperty(name = "app.digest.enabled", havingValue = "true")
public class DigestTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1019;

    private static final Logger LOG = LoggerFactory.getLogger(DigestTick.class);

    private final PostgresLeaderLock leaderLock;
    private final DigestRepository repository;
    private final TaskService taskService;
    private final DigestProperties properties;
    private final Clock clock;

    /**
     * @param leaderLock  leader-lock
     * @param repository  кому пора
     * @param taskService постановка заданий
     * @param properties  время письма
     * @param clock       часы
     */
    public DigestTick(PostgresLeaderLock leaderLock, DigestRepository repository, TaskService taskService,
            DigestProperties properties, Clock clock) {
        this.leaderLock = leaderLock;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Такт. Ошибка БД не прерывает расписание.
     */
    @Scheduled(fixedDelayString = "${app.digest.interval-ms:300000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> {
                for (Due due : repository.due(clock.instant(), properties.sendAt())) {
                    taskService.enqueue(SendDigestHandler.TYPE, SendDigestHandler.taskKey(due.userId(), due.day()),
                            SendDigestHandler.payload(due.userId(), due.day()));
                }
            });
        } catch (DataAccessException exception) {
            LOG.warn("Digest tick failed, will retry on next tick", exception);
        }
    }
}
