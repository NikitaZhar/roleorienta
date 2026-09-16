package com.roleorienta.worker.messaging;

import com.roleorienta.worker.idempotency.ProcessedMessageRepository;
import com.roleorienta.worker.jobs.JobHandler;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.jobs.TypedJobHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * <p>Маршрутизация по типу задания: слушатель собирает все {@link TypedJobHandler}
 * и по {@code eventType} сообщения выбирает нужный; для типов без своего обработчика
 * вызывается запасной {@link JobHandler} (в рабочем окружении — заглушка
 * {@code NoOpJobHandler}). Так обработчики новых типов добавляются отдельными
 * бинами, не меняя ни слушатель, ни доставку.</p>
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
    private final Map<String, TypedJobHandler> handlersByTaskType;
    private final JobHandler fallbackHandler;

    /**
     * @param processedMessages хранилище ключей идемпотентности
     * @param typedHandlers     обработчики конкретных типов заданий
     * @param fallbackHandler   запасной обработчик для типов без своей реализации
     * @throws IllegalStateException если два обработчика заявили один и тот же тип задания
     */
    public JobMessageListener(
            ProcessedMessageRepository processedMessages,
            List<TypedJobHandler> typedHandlers,
            JobHandler fallbackHandler) {
        this.processedMessages = processedMessages;
        this.handlersByTaskType = typedHandlers.stream().collect(Collectors.toMap(
                TypedJobHandler::taskType,
                Function.identity(),
                (first, second) -> {
                    throw new IllegalStateException(
                            "Два обработчика для одного типа задания: " + first.taskType());
                }));
        this.fallbackHandler = fallbackHandler;
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
        String eventType = eventTypeHeader == null ? null : eventTypeHeader.toString();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        JobMessage jobMessage = new JobMessage(idempotencyKey, eventType, payload);

        TypedJobHandler handler = handlersByTaskType.get(eventType);
        if (handler != null) {
            handler.handle(jobMessage);
        } else {
            fallbackHandler.handle(jobMessage);
        }
    }
}
