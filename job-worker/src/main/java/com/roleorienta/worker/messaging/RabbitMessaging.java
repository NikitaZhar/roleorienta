package com.roleorienta.worker.messaging;

/**
 * Имена элементов топологии RabbitMQ, общие для объявления топологии, публикатора
 * и потребителя. Собраны в одном месте, чтобы имена exchange/очередей/ключей
 * маршрутизации не расходились между отправителем и получателем.
 *
 * <p>Класс не создаётся (утилитарный держатель констант).</p>
 */
public final class RabbitMessaging {

    /** Основной обменник фоновых заданий (direct, durable). */
    public static final String EXCHANGE = "job.exchange";

    /** Рабочая очередь фоновых заданий (durable). */
    public static final String WORK_QUEUE = "job.work";

    /** Ключ маршрутизации рабочих заданий. */
    public static final String WORK_ROUTING_KEY = "job.work";

    /** Обменник «мёртвых писем» (DLX): в него уходят отклонённые сообщения. */
    public static final String DEAD_LETTER_EXCHANGE = "job.dlx";

    /** Очередь «мёртвых писем» (DLQ) — сообщения после исчерпания попыток. */
    public static final String DEAD_LETTER_QUEUE = "job.work.dlq";

    private RabbitMessaging() {
    }
}
