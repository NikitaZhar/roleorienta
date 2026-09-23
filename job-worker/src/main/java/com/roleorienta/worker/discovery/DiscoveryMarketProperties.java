package com.roleorienta.worker.discovery;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Целевой рынок пилота для гейта обнаружения (§56, A2 — минимальная версия; паспорт
 * пилота в протоколе Этапа 0: Словакия и Австрия).
 *
 * <p>Страны — английские названия, как их отдаёт провайдер в распределении публикаций
 * (Workday — фасет {@code Location_Country} при {@code Accept-Language: en-US}).
 * Сравнение без учёта регистра. Пустой список — гейт рынка выключен.</p>
 *
 * @param countries названия стран целевого рынка
 */
@ConfigurationProperties(prefix = "app.discovery.market")
public record DiscoveryMarketProperties(
        @DefaultValue({"Slovakia", "Slovak Republic", "Austria"}) List<String> countries) {

    /**
     * Сколько публикаций источника приходится на целевой рынок.
     *
     * @param countryCounts распределение публикаций по странам
     * @return сумма по странам рынка
     */
    public int marketCount(Map<String, Integer> countryCounts) {
        Set<String> market = normalized();
        return countryCounts.entrySet().stream()
                .filter(e -> market.contains(e.getKey().toLowerCase(Locale.ROOT)))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    /** Гейт рынка включён (список стран непуст). */
    public boolean enabled() {
        return !normalized().isEmpty();
    }

    private Set<String> normalized() {
        return countries.stream()
                .map(c -> c.strip().toLowerCase(Locale.ROOT))
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toSet());
    }
}
