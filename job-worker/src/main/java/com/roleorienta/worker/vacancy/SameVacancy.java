package com.roleorienta.worker.vacancy;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Признаки одной вакансии в разных источниках компании (§139; бизнес-описание §4.3, технический документ §16.14):
 * ссылка без схемы, {@code www.} и {@code /} в конце; хост ссылки. Те же преобразования, что в
 * {@link JobPostingRepository#findSameVacancy}.
 */
final class SameVacancy {

    private static final Pattern SCHEME = Pattern.compile("^https?://(www\\.)?");
    private static final Pattern TRAILING_SLASH = Pattern.compile("/+$");

    private SameVacancy() {
    }

    /**
     * @param url ссылка публикации (http(s))
     * @return ссылка в нижнем регистре без схемы, {@code www.} и {@code /} в конце
     */
    static String address(String url) {
        return TRAILING_SLASH.matcher(withoutScheme(url)).replaceFirst("");
    }

    /**
     * @param url ссылка публикации (http(s))
     * @return хост ссылки без {@code www.} и {@code /} за ним
     */
    static String hostPrefix(String url) {
        String rest = withoutScheme(url);
        int end = 0;
        while (end < rest.length() && "/?#:".indexOf(rest.charAt(end)) < 0) {
            end++;
        }
        return rest.substring(0, end) + "/";
    }

    private static String withoutScheme(String url) {
        return SCHEME.matcher(url.toLowerCase(Locale.ROOT)).replaceFirst("");
    }
}
