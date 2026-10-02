package com.roleorienta.worker.task;

import com.roleorienta.worker.messaging.RabbitTopology;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Потребитель рабочих очередей — сбора и поиска (стенограмма §46): по заголовку
 * {@link RabbitTopology#TASK_ID_HEADER} выполняет задание. У каждой очереди своё число обработчиков
 * (атрибут {@code concurrency} аннотации, значение из настроек {@code app.task.*-consumers}).
 *
 * <p>{@link RabbitListener} подписывает метод на очередь; сообщение подтверждается брокеру после
 * успешного возврата из метода. Исключение — сообщение отклоняется без возврата в очередь
 * ({@code default-requeue-rejected: false}) и уходит в DLQ; временные отказы сюда не доходят — их
 * повторяет таблица заданий. https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-annotation-driven.html</p>
 */
@Component
public class TaskListener {

    private final TaskExecutor executor;

    /**
     * @param executor исполнитель заданий
     */
    public TaskListener(TaskExecutor executor) {
        this.executor = executor;
    }

    /**
     * Принимает одно сообщение очереди сбора.
     *
     * @param message сообщение с заголовком id задания
     * @throws AmqpRejectAndDontRequeueException если заголовка нет или он не число — сразу в DLQ
     */
    @RabbitListener(queues = RabbitTopology.WORK_QUEUE, concurrency = "${app.task.collect-consumers:6}")
    public void onMessage(Message message) {
        execute(message);
    }

    /**
     * Принимает одно сообщение очереди поиска.
     *
     * @param message сообщение с заголовком id задания
     * @throws AmqpRejectAndDontRequeueException если заголовка нет или он не число — сразу в DLQ
     */
    @RabbitListener(queues = RabbitTopology.DISCOVERY_QUEUE, concurrency = "${app.task.discovery-consumers:4}")
    public void onDiscoveryMessage(Message message) {
        execute(message);
    }

    private void execute(Message message) {
        Object header = message.getMessageProperties().getHeader(RabbitTopology.TASK_ID_HEADER);
        if (header == null) {
            throw new AmqpRejectAndDontRequeueException("Message without task id header");
        }
        try {
            executor.execute(Long.parseLong(header.toString()));
        } catch (NumberFormatException exception) {
            throw new AmqpRejectAndDontRequeueException("Task id header is not a number: " + header, exception);
        }
    }
}
