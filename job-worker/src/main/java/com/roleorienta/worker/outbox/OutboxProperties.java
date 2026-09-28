package com.roleorienta.worker.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки публикатора outbox ({@code app.outbox.*} в {@code application.yml}).
 *
 * <p>{@link ConfigurationProperties} связывает свойства с полями записи; {@link DefaultValue}
 * задаёт значение, если свойство не указано.
 * https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties</p>
 *
 * @param batchSize      максимум событий в одной пачке публикации
 * @param confirmTimeout сколько ждать подтверждений брокера для пачки
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("5s") Duration confirmTimeout) {
}
