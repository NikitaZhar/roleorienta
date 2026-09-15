package com.roleorienta.worker.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.jobs.JobHandler;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.messaging.RabbitMessaging;
import com.roleorienta.worker.outbox.OutboxPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * Интеграционный тест магистрали доставки на реальных PostgreSQL и RabbitMQ
 * (Testcontainers, §13 техдока). Проверяет сквозной путь и его ключевые свойства:
 * публикацию из outbox, идемпотентность потребителя и уход «отравленного»
 * сообщения в DLQ после исчерпания повторов.
 *
 * <p>Схема создаётся фикстурой {@code /db/delivery-schema.sql} (worker не
 * применяет миграции). Публикатор-тик отключён профилем {@code test}; пачки
 * запускаются вручную.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OutboxDeliveryIntegrationTest.TestHandlerConfig.class})
@Sql(scripts = "/db/delivery-schema.sql")
class OutboxDeliveryIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OutboxPublisher publisher;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private RecordingJobHandler handler;

    @BeforeEach
    void resetState() {
        amqpAdmin.purgeQueue(RabbitMessaging.WORK_QUEUE, false);
        amqpAdmin.purgeQueue(RabbitMessaging.DEAD_LETTER_QUEUE, false);
        handler.reset();
    }

    @Test
    void publishesOutboxEventAndProcessesItOnce() {
        jdbcTemplate.update(
                "INSERT INTO outbox_event(aggregate_type, aggregate_id, event_type, payload) "
                        + "VALUES (?, ?, ?, ?::jsonb)",
                "job", "1", "job.test", "{\"k\":1}");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM outbox_event", Long.class);

        int published = publisher.publishBatch();

        assertThat(published).isEqualTo(1);
        Long markedPublished = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL", Long.class);
        assertThat(markedPublished).isEqualTo(1L);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> handler.timesHandled(String.valueOf(id)) == 1);
        Long processed = jdbcTemplate.queryForObject("SELECT count(*) FROM processed_message", Long.class);
        assertThat(processed).isEqualTo(1L);
    }

    @Test
    void duplicateDeliveryIsProcessedOnce() {
        send("dup-1", "job.test", "{}");
        send("dup-1", "job.test", "{}");

        // Первое сообщение обрабатывается; дубликат (тот же messageId), когда бы он
        // ни был доставлен, отсеивается по идемпотентности и не вызывает обработчик
        // повторно и не добавляет строку в processed_message — поэтому обе проверки
        // ниже устойчивы независимо от момента доставки дубликата.
        await().atMost(Duration.ofSeconds(10))
                .until(() -> handler.timesHandled("dup-1") == 1);
        Long processed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_message WHERE idempotency_key = 'dup-1'", Long.class);
        assertThat(processed).isEqualTo(1L);
    }

    @Test
    void failingMessageIsDeadLetteredAfterRetries() {
        send("fail-1", "fail", "{}");

        Message dead = rabbitTemplate.receive(RabbitMessaging.DEAD_LETTER_QUEUE, 10_000);

        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo("fail-1");
    }

    /**
     * Отправляет сообщение напрямую в обменник заданий (в обход outbox) с заданным
     * messageId — для проверки поведения потребителя.
     */
    private void send(String messageId, String eventType, String payload) {
        MessageProperties properties = MessagePropertiesBuilder.newInstance()
                .setMessageId(messageId)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("eventType", eventType)
                .build();
        Message message = MessageBuilder
                .withBody(payload.getBytes(StandardCharsets.UTF_8))
                .andProperties(properties)
                .build();
        rabbitTemplate.send(RabbitMessaging.EXCHANGE, RabbitMessaging.WORK_ROUTING_KEY, message);
    }

    /**
     * Тестовый обработчик: считает обработанные ключи и бросает исключение для
     * событий типа {@code fail}, чтобы проверить путь повторов и DLQ. Заменяет
     * штатный обработчик-заглушку через {@link Primary}.
     */
    static final class RecordingJobHandler implements JobHandler {

        private final List<String> handledKeys = new CopyOnWriteArrayList<>();

        @Override
        public void handle(JobMessage message) {
            if ("fail".equals(message.eventType())) {
                throw new IllegalStateException("тестовый сбой обработки задания");
            }
            handledKeys.add(message.idempotencyKey());
        }

        long timesHandled(String idempotencyKey) {
            return handledKeys.stream().filter(idempotencyKey::equals).count();
        }

        void reset() {
            handledKeys.clear();
        }
    }

    /**
     * Тестовая конфигурация: подменяет обработчик задания на записывающий.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestHandlerConfig {

        @Bean
        @Primary
        RecordingJobHandler recordingJobHandler() {
            return new RecordingJobHandler();
        }
    }
}
