package com.roleorienta.worker.career;

import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ставит проверку адресов кадровых страниц, введённых пользователями ({@link CareerRequestHandler}, §137), под
 * leader-lock: тик раз в минуту — проверка начинается, не дожидаясь очереди компании (бизнес-описание §5); ключ
 * задания по запросу — запрос проверяется один раз. Запрос, задание которого не выполнилось после всех попыток, —
 * «не подключена» (страница не открылась), а не «в обработке» навсегда (аудит §143). Выключается свойством
 * {@code app.career-request.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.career-request.enabled", havingValue = "true", matchIfMissing = true)
public class CareerRequestTick {

    /** Ключ leader-lock (реестр — {@link PostgresLeaderLock}). */
    static final long LOCK_KEY = 1018;

    /** Запросов за тик не больше. */
    static final int BATCH = 50;

    private static final Logger LOG = LoggerFactory.getLogger(CareerRequestTick.class);

    private final PostgresLeaderLock leaderLock;
    private final TaskService taskService;
    private final CareerRequestRepository requests;

    /**
     * @param leaderLock  leader-lock
     * @param taskService постановка заданий
     * @param requests    запросы пользователей
     */
    public CareerRequestTick(PostgresLeaderLock leaderLock, TaskService taskService,
            CareerRequestRepository requests) {
        this.leaderLock = leaderLock;
        this.taskService = taskService;
        this.requests = requests;
    }

    /**
     * Тик: поставить проверку ожидающих запросов.
     */
    @Scheduled(fixedDelayString = "${app.career-request.interval-ms:60000}")
    public void tick() {
        try {
            leaderLock.runIfLeader(LOCK_KEY, () -> {
                int failed = requests.failWithFailedTask(CareerRequestHandler.KEY_PREFIX);
                if (failed > 0) {
                    LOG.warn("Career requests with failed check task marked not connected: {}", failed);
                }
                requests.pendingWithoutTask(CareerRequestHandler.KEY_PREFIX, BATCH).forEach(id -> taskService
                        .enqueue(CareerRequestHandler.TYPE, CareerRequestHandler.KEY_PREFIX + id,
                                CareerRequestHandler.payload(id)));
            });
        } catch (DataAccessException exception) {
            LOG.warn("Career request tick failed, will retry on next tick", exception);
        }
    }
}
