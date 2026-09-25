package com.roleorienta.worker.digest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ежедневный запуск {@link DigestRun} (A7, §88) по расписанию cron в часовом поясе
 * {@code app.digest.mail.zone}. Включается {@code app.digest.mail.enabled=true}; в тестах
 * выключен (проход вызывается напрямую).
 */
@Component
@ConditionalOnProperty(name = "app.digest.mail.enabled", havingValue = "true")
public class DigestTrigger {

    private static final Logger log = LoggerFactory.getLogger(DigestTrigger.class);

    private final DigestRun digest;

    /**
     * @param digest проход дайджеста
     */
    public DigestTrigger(DigestRun digest) {
        this.digest = digest;
    }

    /** Тик: один проход; ошибка логируется, повтор писем — на следующем тике. */
    @Scheduled(cron = "${app.digest.mail.cron:0 50 6 * * *}", zone = "${app.digest.mail.zone:Europe/Bratislava}")
    public void tick() {
        try {
            digest.run();
        } catch (RuntimeException exception) {
            log.warn("Дайджест: проход завершился ошибкой, повтор на следующем тике", exception);
        }
    }
}
