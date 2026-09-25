package com.roleorienta.worker.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодически запускает {@link DiscoveryHarvestScheduler#runOnce()}.
 *
 * <p>Отделён от планировщика, чтобы проход можно было запускать напрямую (в тестах)
 * без расписания. Отключается свойством {@code app.discovery.harvest.enabled=false}
 * (в тестах). По умолчанию включён.</p>
 */
@Component
@ConditionalOnProperty(name = "app.discovery.harvest.enabled", havingValue = "true", matchIfMissing = true)
public class DiscoveryHarvestTrigger {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryHarvestTrigger.class);

    private final DiscoveryHarvestScheduler scheduler;

    public DiscoveryHarvestTrigger(DiscoveryHarvestScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Тик гарвеста. Ошибки логируются и не прерывают расписание.
     */
    @Scheduled(fixedDelayString = "${app.discovery.harvest.poll-interval-ms:120000}")
    public void tick() {
        try {
            scheduler.runOnce();
        } catch (RuntimeException exception) {
            log.warn("Тик гарвеста обнаружения завершился ошибкой, будет повтор на следующем тике", exception);
        }
    }
}
