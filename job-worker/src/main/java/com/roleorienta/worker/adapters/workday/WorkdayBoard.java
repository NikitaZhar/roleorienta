package com.roleorienta.worker.adapters.workday;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Найденная доска вакансий Workday: всё, что нужно {@link WorkdayAdapter}, чтобы читать
 * ленту (§5, ADR-17). Получается из URL страницы карьеры ({@link #fromCareerUrl}, вход
 * Common Crawl) или словарным резолвом по хосту ({@link WorkdaySiteResolver}).
 *
 * @param tenant  тенант — левая метка хоста {@code <tenant>.wd<N>.myworkdayjobs.com}
 *                (технический идентификатор, <b>не</b> имя работодателя — A2/A11)
 * @param site    сайт карьеры тенанта — сегмент пути cxs ({@code /wday/cxs/<tenant>/<site>/jobs})
 * @param baseUrl origin тенанта без завершающего слэша ({@code https://<tenant>.wd<N>.myworkdayjobs.com})
 */
public record WorkdayBoard(String tenant, String site, String baseUrl) {

    /** Вендорный шаблон хоста тенанта: {@code <tenant>.wd<N>.myworkdayjobs.com}. */
    static final Pattern TENANT_HOST =
            Pattern.compile("^([a-z0-9][a-z0-9_-]*)\\.wd\\d+\\.myworkdayjobs\\.com$");

    /**
     * Сегмент локали в пути страницы карьеры: {@code en-US}, {@code fr-CA}, {@code de}.
     * Язык — строчный; регион — в любом регистре: в индексе Common Crawl встречается и
     * {@code en-us} (стенд §72: {@code uline/en-us} → 404, §73). Двухбуквенный сайт в
     * верхнем регистре (реальный пример — {@code american/AU}) локалью не считается.
     */
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}(-[A-Za-z]{2})?$");

    /** Допустимое имя сайта. */
    static final Pattern SITE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]*$");

    /** Служебные первые сегменты пути Workday, которые не являются именем сайта. */
    private static final Set<String> RESERVED = Set.of("wday", "job", "login", "userhome");

    /**
     * Слаг кандидата и {@code Source.external_ref} в формате, который ждёт
     * {@link WorkdayAdapter}: {@code "<tenant>/<site>"}.
     *
     * @return {@code tenant + "/" + site}
     */
    public String slug() {
        return tenant + "/" + site;
    }

    /**
     * Ключ дедупа: cxs не чувствителен к регистру {@code site} (проверено 2026-09-23),
     * поэтому {@code acme/External} и {@code acme/external} — одна доска.
     *
     * @return {@code slug()} в нижнем регистре
     */
    public String dedupKey() {
        return slug().toLowerCase(Locale.ROOT);
    }

    /**
     * Извлекает доску из URL страницы карьеры Workday:
     * {@code http(s)://<tenant>.wd<N>.myworkdayjobs.com[/<locale>]/<site>[/...]}.
     * {@code baseUrl} всегда {@code https} (Workday обслуживает только его; http-URL
     * встречаются в архивах краулера).
     *
     * @param url адрес страницы (напр. из индекса Common Crawl)
     * @return доска; пусто, если хост не по шаблону Workday или в пути нет имени сайта
     */
    public static Optional<WorkdayBoard> fromCareerUrl(String url) {
        if (url == null) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        if (uri.getHost() == null || uri.getScheme() == null
                || !uri.getScheme().toLowerCase(Locale.ROOT).startsWith("http")) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Matcher matcher = TENANT_HOST.matcher(host);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return siteFromPath(uri.getRawPath())
                .map(site -> new WorkdayBoard(matcher.group(1), site, "https://" + host));
    }

    /**
     * Имя сайта из пути страницы карьеры: {@code /<locale>/<site>/...} или {@code /<site>/...}.
     */
    static Optional<String> siteFromPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        List<String> segments = Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        int index = LOCALE.matcher(segments.get(0)).matches() ? 1 : 0;
        if (index >= segments.size()) {
            return Optional.empty();
        }
        String candidate = segments.get(index);
        if (!SITE.matcher(candidate).matches()
                || RESERVED.contains(candidate.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }
}
