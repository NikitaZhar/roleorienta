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
 * <p><b>Локации</b> (когда фасета стран нет, §59) делятся на три вида (§61):</p>
 * <ul>
 *   <li>{@code locationTerms} — <b>однозначные</b> признаки рынка: страны, коды AUT/SVK,
 *       земли (Styria, Tyrol …) и города, которых нет в других странах в заметном числе
 *       (Bratislava, Graz, Linz …). Любая локация с таким словом — на рынке;</li>
 *   <li>{@code ambiguousLocationTerms} — <b>неоднозначные</b> города: «Vienna» — это и Вена,
 *       и десяток городков США (Vienna, VA / WV / GA). Без однозначного признака рядом
 *       локация не засчитывается как рынок: с маркером США («VA», «USA», «Virginia») —
 *       отбрасывается, без маркера («Vienna») — считается неоднозначной (кандидат уходит
 *       на ручную проверку, а не подключается);</li>
 *   <li>прочие — не рынок.</li>
 * </ul>
 * <p>Совпадение — целым словом, без учёта регистра. Найдено прогоном по ~1000 доскам
 * Workday: 11 из 24 «рыночных по локациям» оказались Vienna в Вирджинии/Западной
 * Вирджинии/Джорджии.</p>
 *
 * @param countries              названия стран целевого рынка
 * @param locationTerms          однозначные признаки локации на рынке
 * @param ambiguousLocationTerms неоднозначные города (засчитываются только с признаком страны)
 */
@ConfigurationProperties(prefix = "app.discovery.market")
public record DiscoveryMarketProperties(
        @DefaultValue({"Slovakia", "Slovak Republic", "Austria"}) List<String> countries,
        @DefaultValue({"Slovakia", "Slovak Republic", "Slovensko", "Bratislava", "Kosice", "Košice",
                "Zilina", "Žilina", "Austria", "Österreich", "Wien", "Graz", "Linz", "Salzburg",
                "Innsbruck", "Styria", "Steiermark", "Upper Austria", "Oberösterreich", "Tyrol", "Tirol",
                "Carinthia", "Kärnten", "SVK", "AUT"}) List<String> locationTerms,
        @DefaultValue({"Vienna"}) List<String> ambiguousLocationTerms) {

    /**
     * Маркеры США в названии локации: двухбуквенные коды штатов (строго заглавными, целым
     * словом) и слова. Нужны только чтобы отбросить неоднозначные города («Vienna, VA»).
     */
    private static final Pattern US_MARKER = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN"
                    + "|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY"
                    + "|US|USA|U\\.S\\.)(?![\\p{L}\\p{N}])"
                    + "|(?i:united states|virginia|georgia|ohio|maryland|illinois|missouri|new york)");

    /**
     * Итог сверки локаций с рынком.
     *
     * @param marketCount     публикаций в однозначно рыночных локациях
     * @param ambiguousCount  публикаций в неоднозначных локациях (без маркера страны)
     * @param marketLocations рыночные локации (для объяснения решения)
     * @param ambiguousLocations неоднозначные локации
     */
    public record LocationMatch(int marketCount, int ambiguousCount,
                                List<String> marketLocations, List<String> ambiguousLocations) {
    }

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
        return new DiscoveryMarketProperties(countries, countries, List.of());
    }

    /**
     * Сверяет локации работодателя с рынком (§61): однозначные признаки → рынок;
     * неоднозначные города без маркера США → неоднозначно; с маркером США → не рынок.
     *
     * @param locationCounts распределение публикаций по локациям
     * @return счётчики и сами локации
     */
    public LocationMatch matchLocations(Map<String, Integer> locationCounts) {
        Pattern strong = wholeWords(locationTerms);
        Pattern ambiguous = wholeWords(ambiguousLocationTerms);
        int market = 0;
        int unsure = 0;
        List<String> marketLocations = new java.util.ArrayList<>();
        List<String> unsureLocations = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : locationCounts.entrySet()) {
            String location = e.getKey();
            if (strong != null && strong.matcher(location).find()) {
                market += e.getValue();
                marketLocations.add(location + " " + e.getValue());
            } else if (ambiguous != null && ambiguous.matcher(location).find()
                    && !US_MARKER.matcher(location).find()) {
                unsure += e.getValue();
                unsureLocations.add(location + " " + e.getValue());
            }
        }
        return new LocationMatch(market, unsure, List.copyOf(marketLocations), List.copyOf(unsureLocations));
    }

    /**
     * Сколько публикаций источника приходится на однозначно рыночные локации
     * (см. {@link #matchLocations}).
     *
     * @param locationCounts распределение публикаций по локациям
     * @return сумма по локациям рынка
     */
    public int marketLocationCount(Map<String, Integer> locationCounts) {
        return matchLocations(locationCounts).marketCount();
    }

    private static Pattern wholeWords(List<String> terms) {
        String alternatives = terms.stream()
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
