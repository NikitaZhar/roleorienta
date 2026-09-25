package com.roleorienta.worker.adapters.personio;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Доска вакансий работодателя на Personio (§91): поддомен {@code <slug>.jobs.personio.de} или
 * {@code <slug>.jobs.personio.com}. Один поддомен — один аккаунт работодателя; slug — имя аккаунта.
 *
 * @param slug    имя аккаунта (поддомен), в нижнем регистре
 * @param baseUrl адрес доски {@code https://<slug>.jobs.personio.<de|com>}
 */
public record PersonioBoard(String slug, String baseUrl) {

    /** Хост доски: поддомен аккаунта в {@code jobs.personio.de} или {@code jobs.personio.com}. */
    static final Pattern BOARD_HOST = Pattern.compile("^([a-z0-9][a-z0-9-]*)\\.jobs\\.personio\\.(de|com)$");

    /**
     * Ключ дедупа досок: один аккаунт на {@code .de} и {@code .com} — одна доска.
     *
     * @return slug в нижнем регистре
     */
    public String dedupKey() {
        return slug;
    }

    /**
     * Доска по любому адресу её страниц ({@code /}, {@code /job/123}, {@code /xml}).
     *
     * @param url адрес из индекса Common Crawl
     * @return доска; пусто — адрес не с доски Personio или разобран не полностью
     */
    public static Optional<PersonioBoard> fromCareerUrl(String url) {
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
        Matcher matcher = BOARD_HOST.matcher(host);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new PersonioBoard(matcher.group(1), "https://" + host));
    }
}
