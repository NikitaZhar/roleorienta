package com.roleorienta.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.messaging.RabbitTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Публикатор outbox на реальных PostgreSQL и RabbitMQ: доставленное событие отмечается
 * опубликованным, немаршрутизируемое остаётся неопубликованным со счётчиком попыток; задание поиска
 * уходит в очередь поиска, прочие — в очередь сбора.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxPublisherTests {

    private static final long RECEIVE_TIMEOUT_MS = 5000;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private Binding workBinding;

    /**
     * Чистое состояние: пустые таблицы outbox и заданий, пустая рабочая очередь.
     */
    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        rabbitAdmin.purgeQueue(RabbitTopology.WORK_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitTopology.DISCOVERY_QUEUE, false);
    }

    /**
     * Событие попадает в рабочую очередь с id события в {@code messageId} и id задания в заголовке
     * и отмечается опубликованным; повторная пачка ничего не отправляет.
     */
    @Test
    void publishesEventAndMarksItPublished() {
        long taskId = insertTask();
        long eventId = insertEvent(taskId);

        assertThat(publisher.publishBatch()).isEqualTo(1);

        Message message = rabbitTemplate.receive(RabbitTopology.WORK_QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(String.valueOf(eventId));
        assertThat(message.getMessageProperties().<Object>getHeader(RabbitTopology.TASK_ID_HEADER))
                .hasToString(String.valueOf(taskId));
        assertThat(isPublished(eventId)).isTrue();
        assertThat(publisher.publishBatch()).isZero();
    }

    /**
     * Без привязки очереди сообщение возвращается брокером: событие не отмечено, попытка
     * засчитана; после восстановления привязки уходит следующей пачкой.
     */
    @Test
    void leavesUnroutableEventUnpublished() {
        long eventId = insertEvent(insertTask());
        rabbitAdmin.removeBinding(workBinding);
        try {
            assertThat(publisher.publishBatch()).isEqualTo(1);
            assertThat(isPublished(eventId)).isFalse();
            assertThat(attempts(eventId)).isEqualTo(1);
        } finally {
            rabbitAdmin.declareBinding(workBinding);
        }

        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(isPublished(eventId)).isTrue();
    }

    /**
     * Задание поиска (государственный портал) — в очередь поиска, не в очередь сбора.
     */
    @Test
    void routesDiscoveryTaskToDiscoveryQueue() {
        long taskId = insertTask("STATE_PORTAL");
        insertEvent(taskId);

        assertThat(publisher.publishBatch()).isEqualTo(1);

        Message message = rabbitTemplate.receive(RabbitTopology.DISCOVERY_QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().<Object>getHeader(RabbitTopology.TASK_ID_HEADER))
                .hasToString(String.valueOf(taskId));
        assertThat(rabbitTemplate.receive(RabbitTopology.WORK_QUEUE)).isNull();
    }

    private long insertTask() {
        return insertTask("TEST");
    }

    private long insertTask(String type) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO task (type, task_key, payload, state)
                VALUES (?, gen_random_uuid()::text, '{}', 'QUEUED')
                RETURNING id
                """, Long.class, type);
    }

    private long insertEvent(long taskId) {
        outboxRepository.insertTaskEvent(taskId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_event WHERE task_id = ?", Long.class, taskId);
    }

    private boolean isPublished(long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_event WHERE id = ?", Boolean.class, eventId);
    }

    private int attempts(long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, eventId);
    }
}
