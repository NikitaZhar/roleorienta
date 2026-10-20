package com.roleorienta.worker.outbox;

import com.roleorienta.worker.messaging.RabbitTopology;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Публикатор transactional outbox: отправляет неопубликованные события (задание поставлено в
 * очередь) в RabbitMQ и отмечает их опубликованными (технический документ §6, §9).
 *
 * <p>Одна пачка — одна транзакция БД: захват событий ({@code FOR UPDATE SKIP LOCKED}) →
 * отправка каждого с {@code mandatory} и своим {@link CorrelationData} → ожидание подтверждения
 * каждого → отметка {@code published_at}. Подтверждение брокера означает только приём, а не попадание в
 * очередь, поэтому сообщения, вернувшиеся как немаршрутизируемые ({@code basic.return}), не отмечаются:
 * у них растёт счётчик попыток, и они уйдут в следующей пачке.</p>
 *
 * <p>Возврат узнаётся из {@link CorrelationData#getReturned()}: Spring AMQP записывает вернувшееся
 * сообщение в {@code CorrelationData} раньше, чем завершает его подтверждение
 * ({@link CorrelationData#getFuture()}). Прежняя схема — общий {@code waitForConfirmsOrDie} и набор
 * вернувшихся из обратного вызова — гонялась: обратный вызов возврата выполняется в другом потоке и мог
 * прийти после конца ожидания, и вернувшееся сообщение отмечалось опубликованным (стенограмма §153). Id
 * подтверждения — id события и случайная часть: после истечения срока пачки событие отправляется снова, и поздний
 * возврат прежней отправки не должен приписаться новой (аудит §154).
 * https://docs.spring.io/spring-amqp/reference/amqp/template.html#template-confirms</p>
 *
 * <p>Если подтверждений нет в срок пачки или брокер ответил отказом ({@code nack}), бросается
 * {@link AmqpException}, транзакция откатывается и ни одно событие пачки не отмечается — повтор на
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
     * @param repository     доступ к таблице outbox
     * @param rabbitTemplate шаблон отправки; {@code mandatory} и подтверждения включены в настройках
     * @param properties     размер пачки и тайм-аут подтверждений
     */
    public OutboxPublisher(OutboxRepository repository, RabbitTemplate rabbitTemplate,
            OutboxProperties properties) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * Публикует одну пачку неопубликованных событий. Пачки одной и разных реплик не мешают друг другу:
     * события захватываются {@code SKIP LOCKED}, у каждого сообщения — свой {@link CorrelationData}.
     *
     * @return число захваченных событий; 0 — публиковать нечего
     * @throws AmqpException если брокер недоступен, отказал или не подтвердил пачку в срок; ни одно
     *         событие пачки не отмечено опубликованным
     */
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> batch = repository.claimUnpublished(properties.batchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        List<CorrelationData> confirms = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            CorrelationData correlation = new CorrelationData(event.id() + ":" + UUID.randomUUID());
            rabbitTemplate.send(RabbitTopology.EXCHANGE, RabbitTopology.routingKey(event.taskType()), toMessage(event),
                    correlation);
            confirms.add(correlation);
        }
        long deadline = System.nanoTime() + properties.confirmTimeout().toNanos();
        for (CorrelationData correlation : confirms) {
            awaitAck(correlation, deadline);
        }
        for (int index = 0; index < batch.size(); index++) {
            long eventId = batch.get(index).id();
            if (confirms.get(index).getReturned() != null) {
                repository.incrementAttempts(eventId);
                LOG.warn("Outbox event id={} returned as unroutable, left unpublished", eventId);
            } else {
                repository.markPublished(eventId);
            }
        }
        return batch.size();
    }

    /**
     * Ждёт подтверждения сообщения до общего срока пачки.
     *
     * @param correlation подтверждение сообщения
     * @param deadline    срок пачки ({@link System#nanoTime()})
     * @throws AmqpException срок вышел, брокер отказал ({@code nack}) или ожидание прервано
     */
    private static void awaitAck(CorrelationData correlation, long deadline) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!confirm.ack()) {
                throw new AmqpException("Broker rejected outbox message " + correlation.getId() + ": "
                        + confirm.reason());
            }
        } catch (TimeoutException timeout) {
            throw new AmqpException("Broker did not confirm outbox message " + correlation.getId() + " in time",
                    timeout);
        } catch (ExecutionException failure) {
            throw new AmqpException("Confirm of outbox message " + correlation.getId() + " failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while waiting for outbox confirms", interrupted);
        }
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
