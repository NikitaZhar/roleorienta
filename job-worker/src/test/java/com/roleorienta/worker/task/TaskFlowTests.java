package com.roleorienta.worker.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.messaging.RabbitTopology;
import com.roleorienta.worker.outbox.OutboxPublisher;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Задания на реальных PostgreSQL и RabbitMQ: постановка, выполнение, идемпотентность, повтор через
 * БД, перепостановка, исчерпание попыток, ошибка кода → DLQ.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TaskFlowTests.ScriptedHandlerConfig.class})
class TaskFlowTests {

    private static final String TEST_TYPE = "TEST";
    private static final String PAYLOAD = "{}";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 100;
    private static final long RECEIVE_TIMEOUT_MS = 5000;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private ScriptedHandler handler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /**
     * Чистые таблицы и очереди, обработчик по умолчанию завершает задание.
     */
    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        rabbitAdmin.purgeQueue(RabbitTopology.WORK_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitTopology.DEAD_LETTER_QUEUE, false);
        handler.reset(TaskOutcome.Done::new);
    }

    /**
     * Потребитель остановлен после каждого теста (в профиле test он не стартует сам).
     */
    @AfterEach
    void stopListener() {
        listenerRegistry.stop();
    }

    /**
     * Повторная постановка с тем же ключом ничего не делает; задание проходит через outbox,
     * брокер и потребителя и завершается; обработчик вызван один раз.
     */
    @Test
    void enqueuedTaskIsDeliveredAndCompleted() throws InterruptedException {
        assertThat(taskService.enqueue(TEST_TYPE, "k-done", PAYLOAD)).isTrue();
        assertThat(taskService.enqueue(TEST_TYPE, "k-done", PAYLOAD)).isFalse();
        long taskId = taskId("k-done");

        listenerRegistry.start();
        assertThat(publisher.publishBatch()).isEqualTo(1);

        awaitState(taskId, "DONE");
        assertThat(handler.calls()).isEqualTo(1);
    }

    /**
     * Повторное выполнение завершённого задания (повторная доставка) не вызывает обработчик.
     */
    @Test
    void completedTaskIsNotExecutedAgain() {
        taskService.enqueue(TEST_TYPE, "k-once", PAYLOAD);
        long taskId = taskId("k-once");

        executor.execute(taskId);
        executor.execute(taskId);

        assertThat(state(taskId)).isEqualTo("DONE");
        assertThat(handler.calls()).isEqualTo(1);
    }

    /**
     * Временный отказ: задание ждёт повтора; когда срок наступил, перепостановка снова ставит его
     * в очередь с новым событием outbox.
     */
    @Test
    void retriedTaskWaitsAndIsRequeuedWhenDue() {
        handler.reset(() -> new TaskOutcome.Retry("timeout", Duration.ZERO));
        taskService.enqueue(TEST_TYPE, "k-retry", PAYLOAD);
        long taskId = taskId("k-retry");

        executor.execute(taskId);

        assertThat(state(taskId)).isEqualTo("WAITING");
        assertThat(attempts(taskId)).isEqualTo(1);
        assertThat(taskService.requeueDue()).isZero();

        jdbcTemplate.update("UPDATE task SET next_attempt_at = now() - interval '1 second' WHERE id = ?", taskId);
        assertThat(taskService.requeueDue()).isEqualTo(1);
        assertThat(state(taskId)).isEqualTo("QUEUED");
        assertThat(unpublishedEvents(taskId)).isEqualTo(2);
    }

    /**
     * Последняя допустимая попытка с временным отказом завершает задание неудачей.
     */
    @Test
    void retryAfterLastAttemptFailsTask() {
        handler.reset(() -> new TaskOutcome.Retry("timeout", Duration.ZERO));
        taskService.enqueue(TEST_TYPE, "k-exhausted", PAYLOAD);
        long taskId = taskId("k-exhausted");
        jdbcTemplate.update("UPDATE task SET attempts = 7 WHERE id = ?", taskId);

        executor.execute(taskId);

        assertThat(state(taskId)).isEqualTo("FAILED");
        assertThat(attempts(taskId)).isEqualTo(8);
    }

    /**
     * Истёкшая аренда (процесс упал во время выполнения) — задание снова в очереди.
     */
    @Test
    void expiredLeaseIsRequeued() {
        taskService.enqueue(TEST_TYPE, "k-lease", PAYLOAD);
        long taskId = taskId("k-lease");
        jdbcTemplate.update(
                "UPDATE task SET state = 'RUNNING', lease_until = now() - interval '1 second' WHERE id = ?",
                taskId);

        assertThat(taskService.requeueDue()).isEqualTo(1);
        assertThat(state(taskId)).isEqualTo("QUEUED");
    }

    /**
     * Цепочка идёт (незавершённое задание её типа) — новая не ставится; завершилась — ставится; задание, не
     * менявшееся больше суток (потерянное сообщение), цепочку не держит (стенограмма §70).
     */
    @Test
    void newChainStartsOnlyWhenPreviousOneEnded() {
        assertThat(taskService.startChain("CHAIN", "chain:day-1:1", PAYLOAD)).isTrue();
        assertThat(taskService.startChain("CHAIN", "chain:day-2:1", PAYLOAD)).isFalse();

        jdbcTemplate.update("UPDATE task SET state = 'DONE' WHERE task_key = 'chain:day-1:1'");
        assertThat(taskService.startChain("CHAIN", "chain:day-2:1", PAYLOAD)).isTrue();

        jdbcTemplate.update("UPDATE task SET updated_at = now() - interval '2 days' WHERE task_key = 'chain:day-2:1'");
        assertThat(taskService.startChain("CHAIN", "chain:day-3:1", PAYLOAD)).isTrue();
    }

    /**
     * Исключение обработчика: задание FAILED, исключение пробрасывается; через брокер сообщение
     * уходит в DLQ.
     */
    @Test
    void handlerExceptionFailsTaskAndSendsMessageToDeadLetterQueue() throws InterruptedException {
        handler.reset(() -> {
            throw new IllegalStateException("bug");
        });
        taskService.enqueue(TEST_TYPE, "k-bug", PAYLOAD);
        long taskId = taskId("k-bug");

        listenerRegistry.start();
        publisher.publishBatch();

        awaitState(taskId, "FAILED");
        assertThat(rabbitTemplate.receive(RabbitTopology.DEAD_LETTER_QUEUE, RECEIVE_TIMEOUT_MS)).isNotNull();
    }

    /**
     * БД временно недоступна во время обработки (транзакция не открылась) — временный отказ:
     * задание ждёт повтора, а не завершается неудачей; цепочка заданий не обрывается.
     */
    @Test
    void unavailableDatabaseDuringHandlingIsRetried() {
        handler.reset(() -> {
            throw new CannotCreateTransactionException("Could not open JPA EntityManager for transaction");
        });
        taskService.enqueue(TEST_TYPE, "k-db-down", PAYLOAD);
        long taskId = taskId("k-db-down");

        executor.execute(taskId);

        assertThat(state(taskId)).isEqualTo("WAITING");
        assertThat(attempts(taskId)).isEqualTo(1);
    }

    /**
     * Ошибка данных (нарушение ограничения БД) — не временный отказ: задание FAILED.
     */
    @Test
    void dataIntegrityViolationFailsTask() {
        handler.reset(() -> {
            throw new DataIntegrityViolationException("value too long");
        });
        taskService.enqueue(TEST_TYPE, "k-data", PAYLOAD);
        long taskId = taskId("k-data");

        assertThatThrownBy(() -> executor.execute(taskId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(state(taskId)).isEqualTo("FAILED");
    }

    /**
     * Задание неизвестного типа завершается неудачей.
     */
    @Test
    void taskWithoutHandlerFails() {
        taskService.enqueue("UNKNOWN", "k-unknown", PAYLOAD);
        long taskId = taskId("k-unknown");

        assertThatThrownBy(() -> executor.execute(taskId)).isInstanceOf(IllegalStateException.class);
        assertThat(state(taskId)).isEqualTo("FAILED");
    }

    private long taskId(String taskKey) {
        return jdbcTemplate.queryForObject("SELECT id FROM task WHERE task_key = ?", Long.class, taskKey);
    }

    private String state(long taskId) {
        return jdbcTemplate.queryForObject("SELECT state FROM task WHERE id = ?", String.class, taskId);
    }

    private int attempts(long taskId) {
        return jdbcTemplate.queryForObject("SELECT attempts FROM task WHERE id = ?", Integer.class, taskId);
    }

    private int unpublishedEvents(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE task_id = ? AND published_at IS NULL",
                Integer.class, taskId);
    }

    private void awaitState(long taskId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (!expected.equals(state(taskId)) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS);
        }
        assertThat(state(taskId)).isEqualTo(expected);
    }

    /**
     * Обработчик заданий типа {@code TEST} с заданным из теста результатом.
     */
    static class ScriptedHandler implements TaskHandler {

        private final AtomicReference<Supplier<TaskOutcome>> outcome = new AtomicReference<>();
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public String type() {
            return TEST_TYPE;
        }

        @Override
        public TaskOutcome handle(TaskRecord task) {
            callCount.incrementAndGet();
            return outcome.get().get();
        }

        void reset(Supplier<TaskOutcome> nextOutcome) {
            outcome.set(nextOutcome);
            callCount.set(0);
        }

        int calls() {
            return callCount.get();
        }
    }

    /**
     * Регистрирует {@link ScriptedHandler} бином.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedHandlerConfig {

        /**
         * @return обработчик заданий типа {@code TEST}
         */
        @Bean
        ScriptedHandler scriptedHandler() {
            return new ScriptedHandler();
        }
    }
}
