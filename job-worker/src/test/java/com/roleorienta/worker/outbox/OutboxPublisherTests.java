package com.roleorienta.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.messaging.RabbitTopology;
import java.nio.charset.StandardCharsets;
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
 * опубликованным, немаршрутизируемое остаётся неопубликованным со счётчиком попыток.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxPublisherTests {

    private static final String PAYLOAD = "{\"taskId\": 1}";
    private static final long RECEIVE_TIMEOUT_MS = 5000;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private Binding workBinding;

    /**
     * Чистое состояние: пустые таблица outbox и рабочая очередь.
     */
    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        rabbitAdmin.purgeQueue(RabbitTopology.WORK_QUEUE, false);
    }

    /**
     * Событие попадает в рабочую очередь с id в {@code messageId} и отмечается опубликованным;
     * повторная пачка ничего не отправляет.
     */
    @Test
    void publishesEventAndMarksItPublished() {
        long eventId = insertEvent();

        assertThat(publisher.publishBatch()).isEqualTo(1);

        Message message = rabbitTemplate.receive(RabbitTopology.WORK_QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(String.valueOf(eventId));
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
        assertThat(isPublished(eventId)).isTrue();
        assertThat(publisher.publishBatch()).isZero();
    }

    /**
     * Без привязки очереди сообщение возвращается брокером: событие не отмечено, попытка
     * засчитана; после восстановления привязки уходит следующей пачкой.
     */
    @Test
    void leavesUnroutableEventUnpublished() {
        long eventId = insertEvent();
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

    private long insertEvent() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO outbox_event (event_type, payload) VALUES ('TEST', ?::jsonb) RETURNING id",
                Long.class, PAYLOAD);
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
