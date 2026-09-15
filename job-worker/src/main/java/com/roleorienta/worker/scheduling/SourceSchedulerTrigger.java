package com.roleorienta.worker.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодически запускает {@link SourceScheduler#runOnce()}.
 *
 * <p>Отделён от планировщика, чтобы проход можно было запускать напрямую (в тестах)
 * без участия расписания. Отключается свойством
 * {@code app.scheduler.enabled=false} (в тестах). По умолчанию включён.</p>
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SourceSchedulerTrigger {

    private static final Logger log = LoggerFactory.getLogger(SourceSchedulerTrigger.class);

    private final SourceScheduler scheduler;

    /**
     * @param scheduler планировщик источников
     */
    public SourceSchedulerTrigger(SourceScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Тик планирования. Ошибки логируются и не прерывают расписание.
     */
    @Scheduled(fixedDelayString = "${app.scheduler.poll-interval-ms:60000}")
    public void tick() {
        try {
            scheduler.runOnce();
        } catch (RuntimeException e) {
            log.warn("Тик планировщика завершился ошибкой, будет повтор на следующем тике", e);
        }
    }
}
