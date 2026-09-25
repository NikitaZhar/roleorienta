package com.roleorienta.worker.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодически запускает {@link OutboxPublisher#publishBatch()}.
 *
 * <p>Отделён от публикатора, чтобы логика публикации оставалась вызываемой
 * напрямую (в тестах) без участия планировщика. Отключается свойством
 * {@code app.outbox.scheduler.enabled=false} (используется в тестах, где пачки
 * запускаются вручную). По умолчанию включён.</p>
 *
 * <p>Публикатор безопасен на нескольких репликах за счёт
 * {@code FOR UPDATE SKIP LOCKED}, поэтому этот тик под leader-lock не ставится
 * (в отличие от будущего планировщика заданий — ADR-12).</p>
 */
@Component
@ConditionalOnProperty(name = "app.outbox.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    private final OutboxPublisher publisher;

    /**
     * @param publisher публикатор outbox
     */
    public OutboxPublisherScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Тик публикации. Ошибки логируются и не прерывают расписание: неотправленные
     * события останутся неопубликованными и уйдут в следующую пачку.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void tick() {
        try {
            publisher.publishBatch();
        } catch (RuntimeException exception) {
            log.warn("Тик публикатора outbox завершился ошибкой, будет повтор на следующем тике", exception);
        }
    }
}
