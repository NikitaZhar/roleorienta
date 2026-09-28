package com.roleorienta.worker.outbox;

import com.roleorienta.worker.messaging.RabbitTopology;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Публикатор transactional outbox: отправляет неопубликованные события (задание поставлено в
 * очередь) в RabbitMQ и отмечает их опубликованными (технический документ §6, §9).
 *
 * <p>Одна пачка — одна транзакция БД: захват событий ({@code FOR UPDATE SKIP LOCKED}) →
 * отправка каждого с {@code mandatory} → ожидание подтверждений брокера → отметка
 * {@code published_at}. Подтверждение брокера означает только приём, а не попадание в очередь,
 * поэтому сообщения, вернувшиеся как немаршрутизируемые ({@code basic.return}), не отмечаются:
 * у них растёт счётчик попыток, и они уйдут в следующей пачке.</p>
 *
 * <p>Если подтверждений нет в срок или брокер ответил отказом, {@code waitForConfirmsOrDie}
 * бросает исключение, транзакция откатывается и ни одно событие пачки не отмечается — повтор на
 * следующем тике. Повторная отправка уже доставленного сообщения возможна; потребитель
 * идемпотентен: задание выполняется, только если его удалось захватить из состояния QUEUED.
 * Транзакция держится на время ожидания подтверждений, поэтому
 * размер пачки и тайм-аут ограничены настройками.</p>
 */
@Service
public class OutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final OutboxProperties properties;

    /**
     * Идентификаторы сообщений текущей пачки, вернувшихся как немаршрутизируемые. Заполняется
     * обратным вызовом клиента AMQP в его потоке, поэтому набор потокобезопасный.
     */
    private final Set<String> returnedMessageIds = ConcurrentHashMap.newKeySet();

    /**
     * @param repository     доступ к таблице outbox
     * @param rabbitTemplate шаблон отправки; {@code mandatory} и подтверждения включены в настройках
     * @param properties     размер пачки и тайм-аут подтверждений
     */
    public OutboxPublisher(OutboxRepository repository, RabbitTemplate rabbitTemplate,
            OutboxProperties properties) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.rabbitTemplate.setReturnsCallback(returned ->
                returnedMessageIds.add(returned.getMessage().getMessageProperties().getMessageId()));
    }

    /**
     * Публикует одну пачку неопубликованных событий.
     *
     * <p>{@code synchronized}: набор вернувшихся сообщений общий для пачки, поэтому пачки одного
     * публикатора не выполняются параллельно; параллельность между репликами обеспечивает
     * {@code SKIP LOCKED}.</p>
     *
     * @return число захваченных событий; 0 — публиковать нечего
     * @throws org.springframework.amqp.AmqpException если брокер недоступен, отказал или не
     *         подтвердил пачку в срок; ни одно событие пачки не отмечено опубликованным
     */
    @Transactional
    public synchronized int publishBatch() {
        List<OutboxEvent> batch = repository.claimUnpublished(properties.batchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        returnedMessageIds.clear();
        rabbitTemplate.invoke(operations -> {
            for (OutboxEvent event : batch) {
                operations.send(RabbitTopology.EXCHANGE, RabbitTopology.ROUTING_KEY, toMessage(event));
            }
            operations.waitForConfirmsOrDie(properties.confirmTimeout().toMillis());
            return null;
        });
        for (OutboxEvent event : batch) {
            if (returnedMessageIds.contains(String.valueOf(event.id()))) {
                repository.incrementAttempts(event.id());
                LOG.warn("Outbox event id={} returned as unroutable, left unpublished", event.id());
            } else {
                repository.markPublished(event.id());
            }
        }
        return batch.size();
    }

    /**
     * Собирает сообщение без тела: {@code messageId} — id события, заголовок
     * {@link RabbitTopology#TASK_ID_HEADER} — id задания, доставка persistent (сообщение
     * сохраняется брокером на диск).
     *
     * @param event событие outbox
     * @return сообщение для отправки
     */
    private Message toMessage(OutboxEvent event) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setMessageId(String.valueOf(event.id()));
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setHeader(RabbitTopology.TASK_ID_HEADER, event.taskId());
        return MessageBuilder.withBody(new byte[0]).andProperties(messageProperties).build();
    }
}
