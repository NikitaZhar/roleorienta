package com.roleorienta.worker.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Объявление топологии RabbitMQ (§8 техдока, A15): durable-обменники и очереди и
 * привязка dead-letter (DLX→DLQ). Всё, что иначе «появлялось бы по умолчанию»
 * незаметно, задано здесь явно.
 *
 * <p>Топология объявляется декларативно бинами {@link DirectExchange},
 * {@link Queue} и {@link Binding}: Spring AMQP при старте создаёт их в брокере,
 * если они отсутствуют. Документация:
 * https://docs.spring.io/spring-amqp/reference/amqp/broker-configuration.html</p>
 *
 * <p>Повторы обработки и отправка исчерпанных сообщений в DLQ настраиваются
 * <b>свойствами</b> {@code spring.rabbitmq.listener.simple.*} (Spring Boot 4
 * использует нативные повторы Spring Framework, отдельная библиотека spring-retry
 * не нужна): {@code retry.enabled}, {@code retry.max-attempts},
 * {@code retry.initial-interval}, {@code retry.multiplier},
 * {@code retry.max-interval} и {@code default-requeue-rejected=false}. При
 * исчерпании попыток сообщение отклоняется без возврата в очередь и уходит в
 * DLX→DLQ. Отправка ({@code mandatory}, publisher confirms) настроена свойствами
 * {@code spring.rabbitmq.template.mandatory} и {@code publisher-confirm-type}.
 * Документация: https://docs.spring.io/spring-boot/reference/messaging/amqp.html</p>
 */
@Configuration
public class RabbitTopologyConfig {

    /**
     * Основной обменник фоновых заданий. Direct: маршрутизация по точному ключу;
     * durable: переживает перезапуск брокера.
     *
     * @return обменник заданий
     */
    @Bean
    public DirectExchange jobExchange() {
        return new DirectExchange(RabbitMessaging.EXCHANGE, true, false);
    }

    /**
     * Обменник «мёртвых писем»: в него брокер перенаправляет сообщения, отклонённые
     * без повторной постановки в очередь.
     *
     * @return dead-letter обменник
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(RabbitMessaging.DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * Рабочая очередь заданий. durable — очередь и сообщения переживают перезапуск
     * брокера; отклонённые сообщения уходят в DLX с тем же ключом маршрутизации.
     * Аргумент {@code x-dead-letter-exchange} — механизм dead-letter брокера.
     * Документация: https://www.rabbitmq.com/docs/dlx
     *
     * @return durable рабочая очередь с привязкой к DLX
     */
    @Bean
    public Queue workQueue() {
        return QueueBuilder.durable(RabbitMessaging.WORK_QUEUE)
                .deadLetterExchange(RabbitMessaging.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RabbitMessaging.WORK_ROUTING_KEY)
                .build();
    }

    /**
     * Очередь «мёртвых писем» для ручного разбора и контролируемого повтора.
     *
     * @return durable очередь DLQ
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(RabbitMessaging.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Привязка рабочей очереди к обменнику заданий по ключу маршрутизации.
     *
     * @return привязка work-очереди
     */
    @Bean
    public Binding workBinding() {
        return BindingBuilder.bind(workQueue()).to(jobExchange()).with(RabbitMessaging.WORK_ROUTING_KEY);
    }

    /**
     * Привязка DLQ к DLX по тому же ключу маршрутизации.
     *
     * @return привязка dead-letter очереди
     */
    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(RabbitMessaging.WORK_ROUTING_KEY);
    }
}
