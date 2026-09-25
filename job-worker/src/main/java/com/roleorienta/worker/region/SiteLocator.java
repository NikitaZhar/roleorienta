package com.roleorienta.worker.region;

import com.roleorienta.worker.http.SourceHttpClient;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

/**
 * Сайт компании по названию из реестра (план R3, §95). В реестре сайта нет: из названия строятся
 * кандидаты домена ({@code <название>.sk}, через дефис, первое слово, {@code .com}), и сайт
 * принимается, только если на главной или странице контактов стоит номер юрлица (IČO) — в
 * Словакии сайт компании обязан его показывать. Совпадение имени домена без номера — не наш сайт.
 */
@Component
public class SiteLocator {

    /** Правовые формы в конце названия: s. r. o., spol. s r. o., a. s., k. s., v. o. s. и т.п. */
    private static final Pattern LEGAL_FORM = Pattern.compile(
            "[,\\s]*(spol\\.?\\s*s\\s*r\\.?\\s*o\\.?|s\\.?\\s*r\\.?\\s*o\\.?|a\\.\\s*s\\.?|k\\.\\s*s\\.?|v\\.\\s*o\\.\\s*s\\.?"
                    + "|j\\.\\s*s\\.\\s*a\\.?|s\\.?\\s*e\\.?|gmbh|ltd\\.?|inc\\.?)\\s*$", Pattern.CASE_INSENSITIVE);

    /** Короче — не домен компании, а случайное слово. */
    private static final int MIN_NAME_LENGTH = 3;

    /** Страницы, где обычно стоит IČO, кроме главной. */
    private static final List<String> CONTACT_PATHS = List.of("/kontakt", "/contact");

    private final SourceHttpClient httpClient;

    /**
     * @param httpClient единый HTTP-клиент воркера
     */
    public SiteLocator(SourceHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Подтверждённый сайт.
     *
     * @param url  адрес главной страницы
     * @param html HTML главной страницы
     */
    public record Site(String url, String html) {
    }

    /**
     * Ищет сайт компании.
     *
     * @param name название из реестра
     * @param ico  номер юрлица
     * @return сайт, на котором найден номер; пусто — ни один кандидат не подтвердился
     */
    public Optional<Site> locate(String name, String ico) {
        for (String domain : candidates(name)) {
            String url = "https://" + domain;
            Optional<String> home = fetch(url);
            if (home.isEmpty()) {
                continue;
            }
            if (showsIco(home.get(), ico)
                    || CONTACT_PATHS.stream().map(path -> fetch(url + path)).flatMap(Optional::stream)
                            .anyMatch(page -> showsIco(page, ico))) {
                return Optional.of(new Site(url, home.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Кандидаты домена по названию: без правовой формы и диакритики, в нижнем регистре.
     *
     * @param name название из реестра
     * @return домены в порядке проверки
     */
    static List<String> candidates(String name) {
        String plain = Normalizer.normalize(LEGAL_FORM.matcher(name.strip()).replaceAll(""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        List<String> words = Arrays.stream(plain.split("[^a-z0-9]+")).filter(word -> !word.isEmpty()).toList();
        Set<String> result = new LinkedHashSet<>();
        if (words.isEmpty()) {
            return List.of();
        }
        String joined = String.join("", words);
        List<String> bases = new ArrayList<>(List.of(joined, String.join("-", words), words.get(0)));
        for (String base : bases) {
            if (base.length() >= MIN_NAME_LENGTH) {
                result.add(base + ".sk");
            }
        }
        if (joined.length() >= MIN_NAME_LENGTH) {
            result.add(joined + ".com");
        }
        return List.copyOf(result);
    }

    /**
     * Стоит ли номер юрлица в тексте страницы (пробелы между цифрами допускаются: «IČO: 35 800 861»).
     *
     * @param html страница
     * @param ico  номер
     * @return {@code true}, если номер найден
     */
    static boolean showsIco(String html, String ico) {
        String digits = Jsoup.parse(html).text().replaceAll("[\\s\\u00a0]", "");
        return ico != null && !ico.isBlank() && digits.contains(ico.strip());
    }

    private Optional<String> fetch(String url) {
        try {
            return Optional.ofNullable(httpClient.getBody(url));
        } catch (RuntimeException unreachable) {
            return Optional.empty();
        }
    }
}
