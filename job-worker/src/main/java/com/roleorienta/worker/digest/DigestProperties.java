package com.roleorienta.worker.digest;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ежедневный дайджест по email (A7, §88). Время запуска — {@code app.digest.mail.cron} (его читает
 * {@link DigestTrigger} вместе с {@code zone}); SMTP-сервер — стандартные настройки
 * {@code spring.mail.*}.
 *
 * @param enabled     включён ли ежедневный запуск ({@link DigestTrigger}); в тестах выключен
 * @param from        адрес отправителя
 * @param zone        часовой пояс расписания и даты в теме письма
 * @param maxAttempts попыток отправки одного письма, после — {@link DigestDeliveryState#FAILED}
 * @param maxItems    строк в разделе письма, остаток — «и ещё N»
 */
@ConfigurationProperties(prefix = "app.digest.mail")
public record DigestProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("digest@roleorienta.local") String from,
        @DefaultValue("Europe/Bratislava") ZoneId zone,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("30") int maxItems) {
}
