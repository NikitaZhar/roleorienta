package com.roleorienta.worker.task;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Выполняет задание: захват (QUEUED → RUNNING) → обработчик → запись результата.
 *
 * <p>Обработчик работает без открытой транзакции: внешние запросы не держат соединение с БД.
 * Идемпотентность: задание выполняется, только если его удалось захватить, — повторно
 * доставленное сообщение пропускается. Временный отказ даёт WAITING с интервалом по
 * {@link RetryPolicy}; исчерпание попыток или окончательная неудача — FAILED. Временная
 * недоступность БД во время обработки (нет соединения, транзакция не открылась, тайм-аут) —
 * тоже временный отказ: задание повторяется, цепочка заданий не обрывается. Другое исключение
 * обработчика (ошибка кода или данных) переводит задание в FAILED и пробрасывается дальше —
 * сообщение уходит в DLQ.</p>
 *
 * <p>Аренда (стенограмма §95): пока обработчик работает, аренда продлевается каждую треть её срока — долгое задание
 * не перепоставляется, пока его исполнитель жив. Продление и запись итога идут по номеру захвата: исполнитель,
 * у которого задание уже забрали (аренда истекла — процесс завис), чужой итог не затирает. Продление ограничено
 * сроком {@code app.task.max-run} (30 мин — как {@code consumer_timeout} RabbitMQ): зависший обработчик не держит
 * задание, его цепочку и группу хоста бесконечно — после срока аренда истекает, задание перепоставляется как
 * неудачная попытка (аудит §95, стенограмма §96). Недоступность БД при
 * захвате не уводит сообщение в DLQ: оно возвращается в очередь ({@link ImmediateRequeueAmqpException}), задание
 * остаётся QUEUED и будет захвачено при следующей доставке. Другая ошибка захвата (ошибка SQL, сбой фиксации) —
 * неудачная попытка: QUEUED → WAITING с повтором, на последней допустимой — FAILED; задание не остаётся QUEUED без
 * сообщения (аудит §95, стенограмма §97).</p>
 */
@Service
public class TaskExecutor implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(TaskExecutor.class);
    /** Продление аренды — каждую такую долю её срока. */
    private static final int RENEWALS_PER_LEASE = 3;

    private final TaskRepository repository;
    private final TaskService taskService;
    private final Map<String, TaskHandler> handlersByType;
    private final TaskProperties properties;
    private final RetryPolicy retryPolicy;
    private final Duration maxRun;
    private final ScheduledExecutorService leaseRenewal = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("task-lease").daemon().factory());

    /**
     * @param repository  доступ к заданиям
     * @param taskService захват заданий (с группами одного хоста)
     * @param handlers    все обработчики заданий ({@link ObjectProvider} — в том числе ни одного)
     * @param properties  настройки заданий
     * @param maxRun      дольше этого аренда не продлевается
     * @throws IllegalStateException если два обработчика заявили один тип
     */
    public TaskExecutor(TaskRepository repository, TaskService taskService, ObjectProvider<TaskHandler> handlers,
            TaskProperties properties, @Value("${app.task.max-run:30m}") Duration maxRun) {
        this.repository = repository;
        this.taskService = taskService;
        this.handlersByType = handlers.orderedStream().collect(Collectors.toMap(
                TaskHandler::type, Function.identity(), (first, second) -> {
                    throw new IllegalStateException("Two handlers for task type " + first.type());
                }));
        this.properties = properties;
        this.retryPolicy = new RetryPolicy(properties.backoff(),
                () -> ThreadLocalRandom.current().nextDouble());
        this.maxRun = maxRun;
    }

    /**
     * Выполняет задание, если его удалось захватить.
     *
     * @param taskId идентификатор задания из сообщения
     * @throws ImmediateRequeueAmqpException если БД недоступна при захвате — сообщение возвращается в очередь
     * @throws RuntimeException              другая ошибка захвата, если и отметить задание не удалось (сообщение —
     *                                       в DLQ)
     * @throws IllegalStateException         если для типа задания нет обработчика (задание — FAILED)
     * @throws RuntimeException              исключение обработчика (задание — FAILED)
     */
    public void execute(long taskId) {
        Optional<ClaimedTask> claimed;
        try {
            claimed = taskService.claim(taskId);
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                | CannotCreateTransactionException databaseUnavailable) {
            LOG.warn("Task id={} not claimed, database unavailable; message returned to queue", taskId,
                    databaseUnavailable);
            throw new ImmediateRequeueAmqpException("Database unavailable while claiming task " + taskId,
                    databaseUnavailable);
        } catch (RuntimeException claimFailure) {
            LOG.error("Task id={} not claimed, counted as a failed attempt", taskId, claimFailure);
            repository.failClaim(taskId, properties.maxAttempts(), properties.backoff().initial(),
                    claimFailure.toString());
            return;
        }
        if (claimed.isEmpty()) {
            LOG.debug("Task id={} is not queued or its group is busy, skipped", taskId);
            return;
        }
        ClaimedTask claim = claimed.get();
        TaskRecord task = claim.task();
        TaskOutcome outcome;
        ScheduledFuture<?> renewal = scheduleRenewal(claim);
        try {
            outcome = handlerFor(task).handle(task);
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                | CannotCreateTransactionException databaseUnavailable) {
            LOG.warn("Task id={} hit unavailable database, will retry", task.id(), databaseUnavailable);
            outcome = new TaskOutcome.Retry(databaseUnavailable.toString(), Duration.ZERO);
        } catch (RuntimeException exception) {
            repository.markFailed(task.id(), claim.claimSeq(), task.attempts() + 1, exception.toString());
            throw exception;
        } finally {
            renewal.cancel(false);
        }
        apply(claim, outcome);
    }

    /**
     * Останавливает продление аренды при остановке приложения: незавершённые задания перепоставятся по аренде.
     */
    @Override
    public void destroy() {
        leaseRenewal.shutdownNow();
    }

    private ScheduledFuture<?> scheduleRenewal(ClaimedTask claim) {
        long period = properties.lease().toMillis() / RENEWALS_PER_LEASE;
        long deadline = System.nanoTime() + maxRun.toNanos();
        AtomicBoolean reported = new AtomicBoolean();
        return leaseRenewal.scheduleAtFixedRate(() -> renew(claim, deadline, reported), period, period,
                TimeUnit.MILLISECONDS);
    }

    private void renew(ClaimedTask claim, long deadline, AtomicBoolean reported) {
        if (System.nanoTime() - deadline > 0) {
            if (!reported.getAndSet(true)) {
                LOG.warn("Task id={} runs longer than {}, lease no longer renewed", claim.task().id(), maxRun);
            }
            return;
        }
        try {
            if (!repository.extendLease(claim.task().id(), claim.claimSeq(), properties.lease())) {
                LOG.warn("Task id={} lease not renewed: task is no longer held by this executor", claim.task().id());
            }
        } catch (DataAccessException exception) {
            LOG.warn("Task id={} lease renewal failed, will retry", claim.task().id(), exception);
        }
    }

    private TaskHandler handlerFor(TaskRecord task) {
        TaskHandler handler = handlersByType.get(task.type());
        if (handler == null) {
            throw new IllegalStateException("No handler for task type " + task.type());
        }
        return handler;
    }

    private void apply(ClaimedTask claim, TaskOutcome outcome) {
        TaskRecord task = claim.task();
        int attempts = task.attempts() + 1;
        switch (outcome) {
            case TaskOutcome.Done done -> repository.markDone(task.id(), claim.claimSeq());
            case TaskOutcome.Failed failed -> repository.markFailed(task.id(), claim.claimSeq(), attempts,
                    failed.reason());
            case TaskOutcome.Retry retry when attempts >= properties.maxAttempts() ->
                    repository.markFailed(task.id(), claim.claimSeq(), attempts, retry.reason());
            case TaskOutcome.Retry retry -> repository.markWaiting(task.id(), claim.claimSeq(), attempts,
                    retryPolicy.delay(attempts, retry.minimumDelay()), retry.reason());
        }
    }
}
