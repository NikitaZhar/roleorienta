package com.roleorienta.worker.digest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодически запускает {@link MatchSubscriptionsScheduler#runOnce()}.
 *
 * <p>Отделён от сопоставления, чтобы проход можно было вызвать напрямую в тестах.
 * Отключается свойством {@code app.digest.match.enabled=false} (в тестах). По умолчанию
 * включён.</p>
 */
@Component
@ConditionalOnProperty(name = "app.digest.match.enabled", havingValue = "true", matchIfMissing = true)
public class MatchSubscriptionsTrigger {

    private static final Logger log = LoggerFactory.getLogger(MatchSubscriptionsTrigger.class);

    private final MatchSubscriptionsScheduler scheduler;

    public MatchSubscriptionsTrigger(MatchSubscriptionsScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Тик сопоставления. Ошибки логируются и не прерывают расписание.
     */
    @Scheduled(fixedDelayString = "${app.digest.match.poll-interval-ms:60000}")
    public void tick() {
        try {
            scheduler.runOnce();
        } catch (RuntimeException e) {
            log.warn("Тик сопоставления подписок завершился ошибкой, будет повтор на следующем тике", e);
        }
    }
}
