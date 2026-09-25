package com.roleorienta.worker.region;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодический запуск {@link RegionRun} (§95). Включается {@code app.region.enabled=true}; в тестах
 * выключен (проход вызывается напрямую).
 */
@Component
@ConditionalOnProperty(name = "app.region.enabled", havingValue = "true")
public class RegionTrigger {

    private static final Logger log = LoggerFactory.getLogger(RegionTrigger.class);

    private final RegionRun region;

    /**
     * @param region проход пути «от региона»
     */
    public RegionTrigger(RegionRun region) {
        this.region = region;
    }

    /** Тик: один проход; ошибка логируется, повтор — на следующем тике. */
    @Scheduled(fixedDelayString = "${app.region.poll-interval-ms:600000}")
    public void tick() {
        try {
            region.run();
        } catch (RuntimeException exception) {
            log.warn("Регион: проход завершился ошибкой, повтор на следующем тике", exception);
        }
    }
}
