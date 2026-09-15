package com.roleorienta.worker.outbox;

import com.roleorienta.worker.messaging.RabbitMessaging;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Публикатор transactional outbox: отправляет накопленные события в RabbitMQ и
 * помечает их опубликованными (§8 техдока, ADR-1/ADR-12).
 *
 * <p>Порядок работы одной пачки (в одной транзакции БД):</p>
 * <ol>
 *   <li>захват неопубликованных событий через {@code FOR UPDATE SKIP LOCKED};</li>
 *   <li>отправка каждого события с {@code mandatory} и publisher confirms;</li>
 *   <li>ожидание подтверждений брокера ({@code waitForConfirmsOrDie});</li>
 *   <li>отметка {@code publishedAt} только для сообщений, которые действительно
 *       были маршрутизированы — немаршрутизированные (пришёл basic.return)
 *       остаются неопубликованными, у них растёт счётчик попыток (A15).</li>
 * </ol>
 *
 * <p>Подтверждение маршрутизации важно: publisher confirm подтверждает приём
 * брокером, но не доставку в очередь. Поэтому включён {@code mandatory}, а
 * возвращённые (немаршрутизируемые) сообщения собираются
 * {@link RabbitTemplate#setReturnsCallback} и исключаются из отметки
 * «опубликовано».</p>
 *
 * <p>Транзакция удерживается на время ожидания подтверждений, поэтому размер
 * пачки и тайм-аут подтверждения ограничены (свойства {@code app.outbox.*}).</p>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final long confirmTimeoutMs;

    /**
     * Идентификаторы сообщений текущей пачки, вернувшихся как немаршрутизируемые.
     * Заполняется callback-ом возврата (в потоке ввода-вывода клиента AMQP),
     * читается публикатором после {@code waitForConfirmsOrDie}. Тип потокобезопасен.
     */
    private final Set<String> returnedMessageIds = ConcurrentHashMap.newKeySet();

    /**
     * @param repository       доступ к таблице outbox
     * @param rabbitTemplate   шаблон отправки (с {@code mandatory})
     * @param batchSize        максимум событий за одну публикацию
     * @param confirmTimeoutMs тайм-аут ожидания подтверждений брокера, мс
     */
    public OutboxPublisher(
            OutboxEventRepository repository,
            RabbitTemplate rabbitTemplate,
            @Value("${app.outbox.batch-size:100}") int batchSize,
            @Value("${app.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.rabbitTemplate.setReturnsCallback(returned ->
                returnedMessageIds.add(returned.getMessage().getMessageProperties().getMessageId()));
    }

    /**
     * Публикует одну пачку неопубликованных событий.
     *
     * <p>Метод single-flight: планировщик вызывает его в один поток; {@code
     * synchronized} защищает общий набор возвратов от случайного параллельного
     * вызова (например, ручного из теста). При нехватке подтверждений или nack
     * {@code waitForConfirmsOrDie} бросает исключение — транзакция откатывается,
     * ни одно событие не помечается опубликованным, пачка повторяется на
     * следующем тике.</p>
     *
     * @return число захваченных в пачку событий (0 — публиковать нечего)
     */
    @Transactional
    public synchronized int publishBatch() {
        List<OutboxEvent> batch = repository.claimUnpublished(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        returnedMessageIds.clear();
        rabbitTemplate.invoke(operations -> {
            for (OutboxEvent event : batch) {
                operations.send(RabbitMessaging.EXCHANGE, RabbitMessaging.WORK_ROUTING_KEY, toMessage(event));
            }
            operations.waitForConfirmsOrDie(confirmTimeoutMs);
            return null;
        });
        Instant now = Instant.now();
        for (OutboxEvent event : batch) {
            if (returnedMessageIds.contains(String.valueOf(event.getId()))) {
                repository.incrementAttempts(event.getId());
                log.warn("Событие outbox id={} немаршрутизируемо (basic.return), оставлено неопубликованным",
                        event.getId());
            } else {
                repository.markPublished(event.getId(), now);
            }
        }
        return batch.size();
    }

    /**
     * Собирает AMQP-сообщение из строки outbox: тело — «сырой» JSON payload,
     * {@code messageId} — идентификатор события (используется потребителем как
     * ключ идемпотентности), режим доставки — persistent (durable-сообщение).
     *
     * @param event строка outbox
     * @return готовое к отправке сообщение
     */
    private Message toMessage(OutboxEvent event) {
        MessageProperties properties = MessagePropertiesBuilder.newInstance()
                .setMessageId(String.valueOf(event.getId()))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("eventType", event.getEventType())
                .setHeader("aggregateType", event.getAggregateType())
                .setHeader("aggregateId", event.getAggregateId())
                .build();
        return MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .andProperties(properties)
                .build();
    }
}
