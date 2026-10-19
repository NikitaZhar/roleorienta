package com.roleorienta.worker.vacancy;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Вакансии других источников компании, к которым присоединяется новая публикация (§139; бизнес-описание §4.3,
 * технический документ §16.14): одна вакансия, если ссылка совпадает без схемы, {@code www.}, регистра хоста и
 * {@code /} в конце (путь и параметры — с учётом регистра), или совпадают провайдер, хост ссылки и внешний id
 * (языковые разделы одной доски). Строится один раз на чтение (аудит §143); при нескольких совпадениях — вакансия с
 * наименьшим id.
 */
final class SameVacancy {

    private static final Pattern SCHEME = Pattern.compile("^(?i)https?://(www\\.)?");
    private static final Pattern TRAILING_SLASH = Pattern.compile("/+$");
    private static final String HOST_END = "/?#";

    private final Map<String, Long> byAddress = new HashMap<>();
    private final Map<String, Long> byExternalId = new HashMap<>();

    private SameVacancy() {
    }

    /**
     * @param rows кандидаты: id вакансии, ссылка, провайдер, внешний id — по возрастанию id вакансии
     *             ({@link JobPostingRepository#findNeighbourPostings})
     * @return поиск по кандидатам
     */
    static SameVacancy of(List<Object[]> rows) {
        SameVacancy same = new SameVacancy();
        for (Object[] row : rows) {
            long vacancyId = ((Number) row[0]).longValue();
            String url = (String) row[1];
            same.byAddress.putIfAbsent(address(url), vacancyId);
            same.byExternalId.putIfAbsent(idKey((String) row[2], host(url), (String) row[3]), vacancyId);
        }
        return same;
    }

    /**
     * @param provider провайдер источника новой публикации
     * @param posting  новая публикация
     * @return вакансия, к которой она присоединяется; пусто — новая вакансия
     */
    Optional<Long> find(String provider, FetchedPosting posting) {
        Long byLink = byAddress.get(address(posting.url()));
        return Optional.ofNullable(byLink != null ? byLink
                : byExternalId.get(idKey(provider, host(posting.url()), posting.externalId())));
    }

    /**
     * @param url ссылка публикации (http(s))
     * @return ссылка без схемы и {@code www.}, хост в нижнем регистре, без {@code /} в конце
     */
    static String address(String url) {
        String rest = withoutScheme(url);
        int end = hostEnd(rest);
        return TRAILING_SLASH.matcher(rest.substring(0, end).toLowerCase(Locale.ROOT) + rest.substring(end))
                .replaceFirst("");
    }

    /**
     * @param url ссылка публикации (http(s))
     * @return хост ссылки без {@code www.}, в нижнем регистре
     */
    static String host(String url) {
        String rest = withoutScheme(url);
        return rest.substring(0, hostEnd(rest)).toLowerCase(Locale.ROOT);
    }

    private static String idKey(String provider, String host, String externalId) {
        return provider + '\n' + host + '\n' + externalId;
    }

    private static String withoutScheme(String url) {
        return SCHEME.matcher(url).replaceFirst("");
    }

    private static int hostEnd(String rest) {
        int end = 0;
        while (end < rest.length() && HOST_END.indexOf(rest.charAt(end)) < 0) {
            end++;
        }
        return end;
    }
}
