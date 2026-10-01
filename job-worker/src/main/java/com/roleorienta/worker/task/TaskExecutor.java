package com.roleorienta.worker.task;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 */
@Service
public class TaskExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(TaskExecutor.class);

    private final TaskRepository repository;
    private final Map<String, TaskHandler> handlersByType;
    private final TaskProperties properties;
    private final RetryPolicy retryPolicy;

    /**
     * @param repository доступ к заданиям
     * @param handlers   все обработчики заданий ({@link ObjectProvider} — в том числе ни одного)
     * @param properties настройки заданий
     * @throws IllegalStateException если два обработчика заявили один тип
     */
    public TaskExecutor(TaskRepository repository, ObjectProvider<TaskHandler> handlers,
            TaskProperties properties) {
        this.repository = repository;
        this.handlersByType = handlers.orderedStream().collect(Collectors.toMap(
                TaskHandler::type, Function.identity(), (first, second) -> {
                    throw new IllegalStateException("Two handlers for task type " + first.type());
                }));
        this.properties = properties;
        this.retryPolicy = new RetryPolicy(properties.backoff(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Выполняет задание, если оно в состоянии QUEUED.
     *
     * @param taskId идентификатор задания
     * @throws IllegalStateException если для типа задания нет обработчика (задание — FAILED)
     * @throws RuntimeException      исключение обработчика (задание — FAILED)
     */
    public void execute(long taskId) {
        Optional<TaskRecord> claimed = repository.claim(taskId, properties.lease());
        if (claimed.isEmpty()) {
            LOG.debug("Task id={} is not queued, skipped", taskId);
            return;
        }
        TaskRecord task = claimed.get();
        TaskOutcome outcome;
        try {
            outcome = handlerFor(task).handle(task);
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                | CannotCreateTransactionException databaseUnavailable) {
            LOG.warn("Task id={} hit unavailable database, will retry", task.id(), databaseUnavailable);
            outcome = new TaskOutcome.Retry(databaseUnavailable.toString(), Duration.ZERO);
        } catch (RuntimeException exception) {
            repository.markFailed(task.id(), task.attempts() + 1, exception.toString());
            throw exception;
        }
        apply(task, outcome);
    }

    private TaskHandler handlerFor(TaskRecord task) {
        TaskHandler handler = handlersByType.get(task.type());
        if (handler == null) {
            throw new IllegalStateException("No handler for task type " + task.type());
        }
        return handler;
    }

    private void apply(TaskRecord task, TaskOutcome outcome) {
        int attempts = task.attempts() + 1;
        switch (outcome) {
            case TaskOutcome.Done done -> repository.markDone(task.id());
            case TaskOutcome.Failed failed -> repository.markFailed(task.id(), attempts, failed.reason());
            case TaskOutcome.Retry retry when attempts >= properties.maxAttempts() ->
                    repository.markFailed(task.id(), attempts, retry.reason());
            case TaskOutcome.Retry retry -> repository.markWaiting(task.id(), attempts,
                    retryPolicy.delay(attempts, retry.minimumDelay()), retry.reason());
        }
    }
}
