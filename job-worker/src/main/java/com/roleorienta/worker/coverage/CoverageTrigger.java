package com.roleorienta.worker.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодический тик проверки покрытия (A5, §82). Включается {@code app.coverage.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.coverage.enabled", havingValue = "true")
public class CoverageTrigger {

    private static final Logger log = LoggerFactory.getLogger(CoverageTrigger.class);

    private final CoverageCheck check;

    /**
     * @param check проход проверки покрытия
     */
    public CoverageTrigger(CoverageCheck check) {
        this.check = check;
    }

    /** Один проход; ошибка логируется, повтор — на следующем тике. */
    @Scheduled(fixedDelayString = "${app.coverage.poll-interval-ms:1800000}")
    public void tick() {
        try {
            check.run();
        } catch (RuntimeException e) {
            log.warn("Тик проверки покрытия завершился ошибкой, повтор на следующем тике", e);
        }
    }
}
