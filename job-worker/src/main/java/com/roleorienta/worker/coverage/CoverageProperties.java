package com.roleorienta.worker.coverage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Проверка покрытия площадкой (A5, §82): как часто и сколько работодателей за проход.
 *
 * @param enabled             включён ли фоновый тик ({@link CoverageTrigger}); в тестах выключен
 * @param pollIntervalMs      период тика, мс
 * @param maxCompaniesPerPass работодателей за проход — по одному запросу к площадке на каждого
 * @param recheckAfterHours   через сколько часов оценка считается устаревшей и проверяется снова
 * @param searchUrl           адрес поиска площадки; к нему добавляется ключевое слово
 */
@ConfigurationProperties(prefix = "app.coverage")
public record CoverageProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1800000") long pollIntervalMs,
        @DefaultValue("3") int maxCompaniesPerPass,
        @DefaultValue("24") long recheckAfterHours,
        @DefaultValue("https://www.karriere.at/jobs/") String searchUrl) {
}
