package com.roleorienta.worker.discovery;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Конфигурация гарвест-входа обнаружения (§5, §36).
 *
 * <p>На пилоте кандидаты в работодатели берутся из <b>курируемого seed</b> — списка
 * записей «провайдер + slug + базовый адрес» (Этап 0: «предзаготовить курируемый
 * список работодателей»). Реальные публичные датасеты (Common Crawl / Certificate
 * Transparency, A11) — следующий срез. {@code maxFanOut} ограничивает число заданий
 * за одно окно (A29): валидный огромный вход не должен давать неограниченный fan-out.</p>
 *
 * @param maxFanOut максимум заданий {@code DISCOVER_EMPLOYER} за одно окно
 * @param seed      курируемый список кандидатов
 */
@ConfigurationProperties(prefix = "app.discovery.harvest")
public record DiscoveryHarvestProperties(
        @DefaultValue("20") int maxFanOut,
        @DefaultValue List<SeedEntry> seed) {

    /**
     * Один кандидат seed.
     *
     * @param providerCode код системы найма (напр. {@code greenhouse})
     * @param slug         идентификатор доски у провайдера
     * @param baseUrl      базовый адрес ленты
     */
    public record SeedEntry(String providerCode, String slug, String baseUrl) {
    }
}
