package com.roleorienta.worker.discovery;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
 * <p>{@code locationTerms} — слова, по которым локация работодателя (офис/город, как его
 * называет тенант) относится к рынку: города, страны, ISO-коды. Нужны, когда провайдер
 * не отдаёт фасет стран (у Workday — тенанты с одной страной, §59). Совпадение — целым
 * словом, без учёта регистра ({@code "AUT.9.Vienna"}, {@code "Bratislava, SK"}).</p>
 *
 * @param countries     названия стран целевого рынка
 * @param locationTerms слова-признаки локации на рынке
 */
@ConfigurationProperties(prefix = "app.discovery.market")
public record DiscoveryMarketProperties(
        @DefaultValue({"Slovakia", "Slovak Republic", "Austria"}) List<String> countries,
        @DefaultValue({"Slovakia", "Slovak Republic", "Slovensko", "Bratislava", "Kosice", "Košice",
                "Zilina", "Žilina", "Austria", "Österreich", "Vienna", "Wien", "Graz", "Linz",
                "Salzburg", "Innsbruck", "SVK", "AUT"}) List<String> locationTerms) {

    /**
     * Рынок только из стран (признаки локаций = названия стран) — для тестов.
     *
     * <p>Фабрика, а не второй конструктор: у record-свойств Spring Boot связывает конфигурацию
     * через единственный конструктор; второй публичный конструктор ломает связывание и
     * подъём контекста (найдено по {@code OutboxDeliveryIntegrationTest}, §59).</p>
     *
     * @param countries названия стран целевого рынка
     * @return свойства рынка
     */
    public static DiscoveryMarketProperties ofCountries(List<String> countries) {
        return new DiscoveryMarketProperties(countries, countries);
    }

    /**
     * Сколько публикаций источника приходится на локации рынка (совпадение слова-признака
     * целым словом в названии локации).
     *
     * @param locationCounts распределение публикаций по локациям
     * @return сумма по локациям рынка
     */
    public int marketLocationCount(Map<String, Integer> locationCounts) {
        Pattern terms = locationPattern();
        if (terms == null) {
            return 0;
        }
        return locationCounts.entrySet().stream()
                .filter(e -> terms.matcher(e.getKey()).find())
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private Pattern locationPattern() {
        String alternatives = locationTerms.stream()
                .map(String::strip)
                .filter(t -> !t.isEmpty())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        if (alternatives.isEmpty()) {
            return null;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

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
