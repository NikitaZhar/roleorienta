package com.roleorienta.worker.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки выдачи ({@code app.delivery.*}).
 *
 * @param hideAfter срок без подтверждения (бизнес-описание §4.3): вакансия, не подтверждённая дольше,
 *                  не выдаётся, но и не закрывается
 */
@ConfigurationProperties("app.delivery")
public record DeliveryProperties(@DefaultValue("30d") Duration hideAfter) {
}
