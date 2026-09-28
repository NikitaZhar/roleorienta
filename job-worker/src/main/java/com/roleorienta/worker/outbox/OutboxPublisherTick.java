package com.roleorienta.worker.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодический запуск {@link OutboxPublisher#publishBatch()}.
 *
 * <p>Отделён от публикатора, чтобы тесты вызывали публикацию напрямую. Выключается свойством
 * {@code app.outbox.tick-enabled=false} ({@link ConditionalOnProperty} не создаёт бин).
 * Под leader-lock не ставится: несколько реплик безопасны благодаря {@code SKIP LOCKED}.</p>
 */
@Component
@ConditionalOnProperty(name = "app.outbox.tick-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherTick {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisherTick.class);

    private final OutboxPublisher publisher;

    /**
     * @param publisher публикатор outbox
     */
    public OutboxPublisherTick(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Тик публикации. Ошибка брокера или БД не прерывает расписание: неотправленные события
     * останутся неопубликованными и уйдут на следующем тике.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void tick() {
        try {
            publisher.publishBatch();
        } catch (AmqpException | DataAccessException exception) {
            LOG.warn("Outbox publish tick failed, will retry on next tick", exception);
        }
    }
}
