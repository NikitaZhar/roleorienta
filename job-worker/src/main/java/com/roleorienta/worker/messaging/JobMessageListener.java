package com.roleorienta.worker.messaging;

import com.roleorienta.worker.idempotency.ProcessedMessageRepository;
import com.roleorienta.worker.jobs.JobHandler;
import com.roleorienta.worker.jobs.JobMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Потребитель фоновых заданий из рабочей очереди.
 *
 * <p>Гарантирует однократный эффект при доставке at-least-once: перед обработкой
 * фиксирует ключ идемпотентности (messageId) в {@code processed_message}; если
 * ключ уже был — сообщение считается обработанным ранее и пропускается (ADR-3).</p>
 *
 * <p>Метод транзакционный: и фиксация ключа, и работа обработчика — в одной
 * транзакции БД. Исключение обработчика откатывает её (в том числе фиксацию
 * ключа), после чего повтором управляет цепочка повторов контейнера, а при
 * исчерпании попыток сообщение уходит в DLQ (см. {@link RabbitTopologyConfig}).</p>
 */
@Component
public class JobMessageListener {

    private static final Logger log = LoggerFactory.getLogger(JobMessageListener.class);

    private final ProcessedMessageRepository processedMessages;
    private final JobHandler jobHandler;

    /**
     * @param processedMessages хранилище ключей идемпотентности
     * @param jobHandler        обработчик задания (абстракция)
     */
    public JobMessageListener(ProcessedMessageRepository processedMessages, JobHandler jobHandler) {
        this.processedMessages = processedMessages;
        this.jobHandler = jobHandler;
    }

    /**
     * Принимает и обрабатывает одно сообщение рабочей очереди.
     *
     * @param message AMQP-сообщение (тело — JSON события, messageId — ключ идемпотентности)
     * @throws AmqpRejectAndDontRequeueException если у сообщения нет messageId
     *         (неожиданный формат — сразу в DLQ, без повторов)
     */
    @RabbitListener(queues = RabbitMessaging.WORK_QUEUE)
    @Transactional
    public void onMessage(Message message) {
        String idempotencyKey = message.getMessageProperties().getMessageId();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Сообщение без messageId — обработка невозможна");
        }
        if (processedMessages.markProcessed(idempotencyKey) == 0) {
            log.debug("Сообщение key={} уже обработано ранее, пропуск (идемпотентность)", idempotencyKey);
            return;
        }
        Object eventTypeHeader = message.getMessageProperties().getHeader("eventType");
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        jobHandler.handle(new JobMessage(
                idempotencyKey,
                eventTypeHeader == null ? null : eventTypeHeader.toString(),
                payload));
    }
}
