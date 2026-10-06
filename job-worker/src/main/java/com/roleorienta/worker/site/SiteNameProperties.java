package com.roleorienta.worker.site;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки шагов 3–4 поиска сайта — адрес по названию ({@code app.site-name.*}; технический документ §5.1, §17).
 *
 * @param companiesPerTask компаний за задание (до 36 адресов на компанию; задание укладывается в аренду)
 * @param recheckAfter     срок до повторной проверки компании без найденного сайта
 */
@ConfigurationProperties("app.site-name")
public record SiteNameProperties(
        @DefaultValue("10") int companiesPerTask,
        @DefaultValue("30d") Duration recheckAfter) {
}
