package com.roleorienta.worker.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Топология RabbitMQ для фоновых заданий (технический документ §9): обменник, рабочая очередь и
 * очередь «мёртвых писем» (DLQ), все durable — переживают перезапуск брокера.
 *
 * <p>Бины {@link DirectExchange}, {@link Queue} и {@link Binding} Spring AMQP при старте
 * объявляет в брокере, если их там нет.
 * https://docs.spring.io/spring-amqp/reference/amqp/broker-configuration.html</p>
 *
 * <p>Сообщение, отклонённое потребителем без возврата в очередь, брокер перекладывает в
 * dead-letter обменник (аргумент очереди {@code x-dead-letter-exchange}), оттуда — в DLQ.
 * https://www.rabbitmq.com/docs/dlx</p>
 */
@Configuration
public class RabbitTopology {

    /** Обменник фоновых заданий; direct — маршрутизация по точному ключу. */
    public static final String EXCHANGE = "roleorienta.jobs";

    /** Рабочая очередь заданий. */
    public static final String WORK_QUEUE = "roleorienta.jobs.work";

    /** Ключ маршрутизации заданий в рабочую очередь. */
    public static final String ROUTING_KEY = "job";

    /** Обменник «мёртвых писем». */
    public static final String DEAD_LETTER_EXCHANGE = "roleorienta.jobs.dlx";

    /** Очередь «мёртвых писем» для разбора и контролируемого повтора. */
    public static final String DEAD_LETTER_QUEUE = "roleorienta.jobs.dlq";

    /** Заголовок сообщения с идентификатором задания. */
    public static final String TASK_ID_HEADER = "taskId";

    /**
     * Обменник фоновых заданий.
     *
     * @return durable direct-обменник
     */
    @Bean
    public DirectExchange jobExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    /**
     * Рабочая очередь; отклонённые сообщения уходят в dead-letter обменник с тем же ключом.
     *
     * @return durable очередь с привязкой к dead-letter обменнику
     */
    @Bean
    public Queue workQueue() {
        return QueueBuilder.durable(WORK_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
    }

    /**
     * Привязка рабочей очереди к обменнику заданий.
     *
     * @param jobExchange обменник заданий
     * @param workQueue   рабочая очередь
     * @return привязка по ключу {@link #ROUTING_KEY}
     */
    @Bean
    public Binding workBinding(DirectExchange jobExchange, Queue workQueue) {
        return BindingBuilder.bind(workQueue).to(jobExchange).with(ROUTING_KEY);
    }

    /**
     * Dead-letter обменник.
     *
     * @return durable direct-обменник
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * Очередь «мёртвых писем».
     *
     * @return durable очередь
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    /**
     * Привязка очереди «мёртвых писем» к dead-letter обменнику.
     *
     * @param deadLetterExchange dead-letter обменник
     * @param deadLetterQueue    очередь «мёртвых писем»
     * @return привязка по ключу {@link #ROUTING_KEY}
     */
    @Bean
    public Binding deadLetterBinding(DirectExchange deadLetterExchange, Queue deadLetterQueue) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(ROUTING_KEY);
    }
}
