package com.roleorienta.worker.messaging;

import java.util.Set;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Топология RabbitMQ для фоновых заданий (технический документ §9): обменник, три рабочие очереди и
 * очередь «мёртвых писем» (DLQ), все durable — переживают перезапуск брокера.
 *
 * <p>Две рабочие очереди (стенограмма §46): <b>сбор</b> ({@link #WORK_QUEUE}) — чтение источников и всё,
 * что идёт к пользователю; <b>поиск</b> ({@link #DISCOVERY_QUEUE}) — реестр, сайты, кадровые страницы,
 * обратный путь, государственный портал. У каждой свои обработчики, поэтому многочасовые цепочки
 * поиска не задерживают чтение вакансий. Третья — <b>адрес по названию</b> ({@link #SITE_NAME_QUEUE},
 * стенограмма §51): до 36 адресов на компанию, цепочка на дни — не задерживает остальной поиск. Очередь
 * выбирается по типу задания ({@link #routingKey}).</p>
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

    /** Ключ маршрутизации заданий в рабочую очередь (сбор); он же — ключ очереди «мёртвых писем». */
    public static final String ROUTING_KEY = "job";

    /** Очередь заданий поиска компаний, сайтов и кадровых страниц. */
    public static final String DISCOVERY_QUEUE = "roleorienta.jobs.discovery";

    /** Ключ маршрутизации заданий поиска. */
    public static final String DISCOVERY_ROUTING_KEY = "discovery";

    /**
     * Типы заданий поиска (константы {@code TYPE} обработчиков: приём реестра, скан сайтов, сайты из Wikidata,
     * проверка кадровых страниц, обратный путь, государственный портал, загрузка справочника GeoNames). Строками,
     * а не ссылками на обработчики: пакет обмена сообщениями не зависит от пакетов обработчиков (иначе
     * цикл пакетов); соответствие проверяет тест. Прочие типы — сбор.
     */
    static final Set<String> DISCOVERY_TYPES = Set.of("REGISTRY_INTAKE", "SITE_SCAN", "SITE_WIKIDATA",
            "CAREER_SCAN", "BOARD_DISCOVERY", "STATE_PORTAL", "GEO_IMPORT");

    /** Очередь заданий «адрес по названию» (шаги 3–4 поиска сайта). */
    public static final String SITE_NAME_QUEUE = "roleorienta.jobs.site-name";

    /** Ключ маршрутизации заданий «адрес по названию». */
    public static final String SITE_NAME_ROUTING_KEY = "site-name";

    /** Тип задания «адрес по названию» ({@code SiteNameHandler.TYPE}; строкой — см. {@link #DISCOVERY_TYPES}). */
    static final String SITE_NAME_TYPE = "SITE_NAME";

    /** Обменник «мёртвых писем». */
    public static final String DEAD_LETTER_EXCHANGE = "roleorienta.jobs.dlx";

    /** Очередь «мёртвых писем» для разбора и контролируемого повтора. */
    public static final String DEAD_LETTER_QUEUE = "roleorienta.jobs.dlq";

    /** Заголовок сообщения с идентификатором задания. */
    public static final String TASK_ID_HEADER = "taskId";

    /**
     * Ключ маршрутизации задания: адрес по названию — в свою очередь, поиск — в очередь поиска, остальное — в
     * рабочую очередь сбора.
     *
     * @param taskType тип задания
     * @return {@link #SITE_NAME_ROUTING_KEY}, {@link #DISCOVERY_ROUTING_KEY} или {@link #ROUTING_KEY}
     */
    public static String routingKey(String taskType) {
        if (SITE_NAME_TYPE.equals(taskType)) {
            return SITE_NAME_ROUTING_KEY;
        }
        return DISCOVERY_TYPES.contains(taskType) ? DISCOVERY_ROUTING_KEY : ROUTING_KEY;
    }

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
     * Очередь заданий поиска; отклонённые сообщения — в тот же dead-letter обменник и ту же DLQ.
     *
     * @return durable очередь с привязкой к dead-letter обменнику
     */
    @Bean
    public Queue discoveryQueue() {
        return QueueBuilder.durable(DISCOVERY_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
    }

    /**
     * Привязка очереди поиска к обменнику заданий.
     *
     * @param jobExchange    обменник заданий
     * @param discoveryQueue очередь поиска
     * @return привязка по ключу {@link #DISCOVERY_ROUTING_KEY}
     */
    @Bean
    public Binding discoveryBinding(DirectExchange jobExchange, Queue discoveryQueue) {
        return BindingBuilder.bind(discoveryQueue).to(jobExchange).with(DISCOVERY_ROUTING_KEY);
    }

    /**
     * Очередь «адрес по названию»; отклонённые сообщения — в тот же dead-letter обменник и ту же DLQ.
     *
     * @return durable очередь с привязкой к dead-letter обменнику
     */
    @Bean
    public Queue siteNameQueue() {
        return QueueBuilder.durable(SITE_NAME_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
    }

    /**
     * Привязка очереди «адрес по названию» к обменнику заданий.
     *
     * @param jobExchange   обменник заданий
     * @param siteNameQueue очередь «адрес по названию»
     * @return привязка по ключу {@link #SITE_NAME_ROUTING_KEY}
     */
    @Bean
    public Binding siteNameBinding(DirectExchange jobExchange, Queue siteNameQueue) {
        return BindingBuilder.bind(siteNameQueue).to(jobExchange).with(SITE_NAME_ROUTING_KEY);
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
