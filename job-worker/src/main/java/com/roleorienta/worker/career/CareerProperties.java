package com.roleorienta.worker.career;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки проверки сайтов компаний на кадровые страницы ({@code app.career.*}).
 *
 * @param scheme       схема адреса сайта ({@code https}; в тестах — {@code http} заглушки)
 * @param sitesPerTask сайтов за одно задание
 * @param recheckAfter срок до перепроверки сайта (технический документ §17)
 */
@ConfigurationProperties("app.career")
public record CareerProperties(
        @DefaultValue("https") String scheme,
        @DefaultValue("50") int sitesPerTask,
        @DefaultValue("30d") Duration recheckAfter) {
}
