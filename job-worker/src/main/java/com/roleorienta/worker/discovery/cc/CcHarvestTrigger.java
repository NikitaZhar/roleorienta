package com.roleorienta.worker.discovery.cc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодически запускает {@link CcHarvestScheduler#runOnce()} (§55).
 *
 * <p><b>По умолчанию выключен</b> ({@code app.discovery.cc.enabled=false}), в отличие от
 * seed-гарвеста: это живой обход внешнего индекса и массовая постановка заданий к лентам
 * Workday — включается только после B1 (бюджет/rate-limit источников, {@code Retry-After})
 * и B2 (потолок тела ответа).</p>
 */
@Component
@ConditionalOnProperty(name = "app.discovery.cc.enabled", havingValue = "true")
public class CcHarvestTrigger {

    private static final Logger log = LoggerFactory.getLogger(CcHarvestTrigger.class);

    private final CcHarvestScheduler scheduler;

    public CcHarvestTrigger(CcHarvestScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Тик. Ошибки логируются и не прерывают расписание.
     */
    @Scheduled(fixedDelayString = "${app.discovery.cc.poll-interval-ms:600000}")
    public void tick() {
        try {
            scheduler.runOnce();
        } catch (RuntimeException exception) {
            log.warn("Тик CC-гарвеста завершился ошибкой, будет повтор на следующем тике", exception);
        }
    }
}
