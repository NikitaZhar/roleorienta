package com.roleorienta.worker.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.messaging.RabbitTopology;
import com.roleorienta.worker.outbox.OutboxPublisher;
import com.roleorienta.worker.outbox.OutboxRepository;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Задания на реальных PostgreSQL и RabbitMQ: постановка, выполнение, идемпотентность, повтор через
 * БД, перепостановка, исчерпание попыток, ошибка кода → DLQ; продление аренды, номер захвата, группа заданий
 * одного хоста и очередь в ней (стенограмма §95, §96). Аренда в тесте — 3 с, предел продления — 6 с, чтобы проверить
 * продление и его предел за секунды.
 */
@SpringBootTest(properties = {"app.task.lease=3s", "app.task.max-run=6s",
        "app.task.groups.exclusive.solo=SOLO_A,SOLO_B"})
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TaskFlowTests.ScriptedHandlerConfig.class})
class TaskFlowTests {

    private static final String TEST_TYPE = "TEST";
    private static final String PAYLOAD = "{}";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 100;
    private static final long RECEIVE_TIMEOUT_MS = 5000;
    /** Дольше аренды теста (3 с): без продления задание за это время было бы перепоставлено. */
    private static final long LONGER_THAN_LEASE_MS = 4500;
    /** Дольше предела продления (6 с) и ещё одной аренды (3 с): аренда должна истечь. */
    private static final long BEYOND_MAX_RUN_MS = 10_500;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private TaskRepository repository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private TaskProperties properties;

    @Autowired
    private ObjectProvider<TaskHandler> handlers;

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
     * Истёкшая аренда (процесс упал во время выполнения) — задание снова в очереди; это неудачная попытка.
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
        assertThat(attempts(taskId)).isEqualTo(1);
    }

    /**
     * Аренда истекла на последней допустимой попытке — задание FAILED и в очередь не ставится: задание, которое
     * каждый раз не укладывается в аренду, не перепоставляется без конца (стенограмма §94).
     */
    @Test
    void expiredLeaseOnLastAttemptFailsTask() {
        taskService.enqueue(TEST_TYPE, "k-lease-last", PAYLOAD);
        long taskId = taskId("k-lease-last");
        jdbcTemplate.update("UPDATE task SET state = 'RUNNING', attempts = 7, lease_until = now() - interval '1 second'"
                + " WHERE id = ?", taskId);

        assertThat(taskService.requeueDue()).isZero();
        assertThat(state(taskId)).isEqualTo("FAILED");
        assertThat(attempts(taskId)).isEqualTo(8);
        assertThat(unpublishedEvents(taskId)).isEqualTo(1);
    }

    /**
     * Пока обработчик работает, аренда продлевается: задание дольше срока аренды не перепоставляется и не
     * выполняется второй копией.
     */
    @Test
    void leaseOfRunningTaskIsRenewed() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        handler.reset(() -> {
            awaitQuietly(release);
            return new TaskOutcome.Done();
        });
        taskService.enqueue(TEST_TYPE, "k-long", PAYLOAD);
        long taskId = taskId("k-long");

        Thread worker = Thread.ofPlatform().start(() -> executor.execute(taskId));
        Thread.sleep(LONGER_THAN_LEASE_MS);

        assertThat(taskService.requeueDue()).isZero();
        assertThat(state(taskId)).isEqualTo("RUNNING");
        release.countDown();
        worker.join();
        assertThat(state(taskId)).isEqualTo("DONE");
        assertThat(handler.calls()).isEqualTo(1);
    }

    /**
     * Продление ограничено пределом {@code app.task.max-run}: зависший обработчик не держит задание бесконечно —
     * аренда истекает, задание перепоставляется как попытка; итог прежнего исполнителя уже не записывается (§96).
     */
    @Test
    void leaseIsNotRenewedBeyondMaxRun() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        handler.reset(() -> {
            awaitQuietly(release);
            return new TaskOutcome.Done();
        });
        taskService.enqueue(TEST_TYPE, "k-hung", PAYLOAD);
        long taskId = taskId("k-hung");

        Thread worker = Thread.ofPlatform().start(() -> executor.execute(taskId));
        Thread.sleep(BEYOND_MAX_RUN_MS);

        assertThat(taskService.requeueDue()).isEqualTo(1);
        assertThat(state(taskId)).isEqualTo("QUEUED");
        assertThat(attempts(taskId)).isEqualTo(1);
        release.countDown();
        worker.join();
        assertThat(state(taskId)).isEqualTo("QUEUED");
    }

    /**
     * Группа достаётся по очереди: продолжение цепочки, поставленное, пока группа была занята, не обгоняет более
     * старое отложенное задание другой цепочки (§96).
     */
    @Test
    void groupIsGivenToOlderWaitingTaskFirst() {
        taskService.enqueue("SOLO_A", "k-order-a1", PAYLOAD);
        taskService.enqueue("SOLO_B", "k-order-b", PAYLOAD);
        long first = taskId("k-order-a1");
        long waiting = taskId("k-order-b");
        ClaimedTask running = taskService.claim(first).orElseThrow();
        assertThat(taskService.claim(waiting)).isEmpty();
        taskService.enqueue("SOLO_A", "k-order-a2", PAYLOAD);
        long continuation = taskId("k-order-a2");
        repository.markDone(first, running.claimSeq());

        assertThat(taskService.claim(continuation)).isEmpty();
        assertThat(state(continuation)).isEqualTo("WAITING");
        jdbcTemplate.update("UPDATE task SET next_attempt_at = now() - interval '1 second' WHERE id = ?", waiting);
        assertThat(taskService.requeueDue()).isEqualTo(1);
        assertThat(taskService.claim(waiting)).isPresent();
    }

    /**
     * Исполнитель, у которого задание забрали (аренда истекла, задание перепоставлено и захвачено снова), итог не
     * пишет: записывает только текущий захват.
     */
    @Test
    void staleExecutorDoesNotOverwriteResult() {
        taskService.enqueue(TEST_TYPE, "k-stale", PAYLOAD);
        long taskId = taskId("k-stale");
        ClaimedTask first = taskService.claim(taskId).orElseThrow();
        jdbcTemplate.update("UPDATE task SET lease_until = now() - interval '1 second' WHERE id = ?", taskId);
        taskService.requeueDue();
        ClaimedTask second = taskService.claim(taskId).orElseThrow();

        repository.markDone(taskId, first.claimSeq());
        assertThat(repository.extendLease(taskId, first.claimSeq(), Duration.ofMinutes(1))).isFalse();
        assertThat(state(taskId)).isEqualTo("RUNNING");

        repository.markDone(taskId, second.claimSeq());
        assertThat(state(taskId)).isEqualTo("DONE");
    }

    /**
     * Задания одной группы (один внешний хост) выполняются по одному: пока задание группы выполняется, второе
     * откладывается без попытки и ставится снова, когда группа свободна.
     */
    @Test
    void groupRunsOneTaskAtATime() {
        taskService.enqueue("SOLO_A", "k-solo-a", PAYLOAD);
        taskService.enqueue("SOLO_B", "k-solo-b", PAYLOAD);
        long first = taskId("k-solo-a");
        long second = taskId("k-solo-b");

        ClaimedTask running = taskService.claim(first).orElseThrow();
        assertThat(taskService.claim(second)).isEmpty();
        assertThat(state(second)).isEqualTo("WAITING");
        assertThat(attempts(second)).isZero();

        repository.markDone(first, running.claimSeq());
        jdbcTemplate.update("UPDATE task SET next_attempt_at = now() - interval '1 second' WHERE id = ?", second);
        assertThat(taskService.requeueDue()).isEqualTo(1);
        assertThat(taskService.claim(second)).isPresent();
    }

    /**
     * Цепочка идёт (незавершённое задание её типа) — новая не ставится; завершилась — ставится; задание, не
     * менявшееся больше суток (потерянное сообщение), цепочку не держит (стенограмма §70). У типа с несколькими
     * цепочками (шаги) цепочка — по началу ключа: идущий шаг не держит другой (§95).
     */
    @Test
    void newChainStartsOnlyWhenPreviousOneEnded() {
        assertThat(taskService.startChain("CHAIN", "chain:step-a:", "chain:step-a:day-1:1", PAYLOAD)).isTrue();
        assertThat(taskService.startChain("CHAIN", "chain:step-a:", "chain:step-a:day-2:1", PAYLOAD)).isFalse();
        assertThat(taskService.startChain("CHAIN", "chain:step-b:", "chain:step-b:day-2:1", PAYLOAD)).isTrue();
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");

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
     * Ошибка захвата (не недоступность БД — например, ошибка SQL) — неудачная попытка: задание ждёт повтора, а не
     * остаётся QUEUED без сообщения; исключение не уходит дальше — сообщение не в DLQ (аудит §95, §97).
     */
    @Test
    void claimFailureIsRetried() {
        taskService.enqueue(TEST_TYPE, "k-claim-failure", PAYLOAD);
        long taskId = taskId("k-claim-failure");

        executorWithClaim(() -> {
            throw new BadSqlGrammarException("claim", "UPDATE task", new SQLException("column missing"));
        }).execute(taskId);

        assertThat(state(taskId)).isEqualTo("WAITING");
        assertThat(attempts(taskId)).isEqualTo(1);
        assertThat(handler.calls()).isZero();
    }

    /**
     * Ошибка захвата на последней допустимой попытке — задание FAILED (§97, §100).
     */
    @Test
    void claimFailureOnLastAttemptFailsTask() {
        taskService.enqueue(TEST_TYPE, "k-claim-last", PAYLOAD);
        long taskId = taskId("k-claim-last");
        jdbcTemplate.update("UPDATE task SET attempts = 7 WHERE id = ?", taskId);

        executorWithClaim(() -> {
            throw new BadSqlGrammarException("claim", "UPDATE task", new SQLException("column missing"));
        }).execute(taskId);

        assertThat(state(taskId)).isEqualTo("FAILED");
        assertThat(attempts(taskId)).isEqualTo(8);
    }

    /**
     * БД недоступна при захвате — сообщение возвращается в очередь ({@link ImmediateRequeueAmqpException}), задание
     * остаётся QUEUED без попытки (§95, §100).
     */
    @Test
    void unavailableDatabaseOnClaimReturnsMessageToQueue() {
        taskService.enqueue(TEST_TYPE, "k-claim-db-down", PAYLOAD);
        long taskId = taskId("k-claim-db-down");
        TaskExecutor failing = executorWithClaim(() -> {
            throw new CannotCreateTransactionException("Could not open JDBC Connection for transaction");
        });

        assertThatThrownBy(() -> failing.execute(taskId)).isInstanceOf(ImmediateRequeueAmqpException.class);
        assertThat(state(taskId)).isEqualTo("QUEUED");
        assertThat(attempts(taskId)).isZero();
    }

    /**
     * Отложенное задание группы, не менявшееся дольше срока ожидания (потерянное сообщение), очередь группы не держит
     * (§96, §100).
     */
    @Test
    void staleWaitingTaskDoesNotHoldGroupQueue() {
        taskService.enqueue("SOLO_A", "k-stale-a", PAYLOAD);
        taskService.enqueue("SOLO_B", "k-stale-b", PAYLOAD);
        long older = taskId("k-stale-a");
        long newer = taskId("k-stale-b");
        jdbcTemplate.update("UPDATE task SET last_error = ?, updated_at = now() - interval '1 hour' WHERE id = ?",
                TaskRepository.GROUP_BUSY, older);

        assertThat(taskService.claim(newer)).isPresent();
    }

    /**
     * Внутренняя очередь (выдача, соответствия) объявлена в брокере (§95, §100).
     */
    @Test
    void internalQueueIsDeclared() {
        assertThat(rabbitAdmin.getQueueProperties(RabbitTopology.INTERNAL_QUEUE)).isNotNull();
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

    /**
     * Исполнитель с подменённым захватом — для ошибок захвата, которые на настоящей БД не воспроизвести.
     */
    private TaskExecutor executorWithClaim(Supplier<Optional<ClaimedTask>> claim) {
        TaskService claims = new TaskService(repository, outboxRepository, properties) {
            @Override
            public Optional<ClaimedTask> claim(long taskId) {
                return claim.get();
            }
        };
        return new TaskExecutor(repository, claims, handlers, properties, Duration.ofMinutes(1));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
