package com.roleorienta.worker.vacancy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Вакансии других источников компании, к которым присоединяется новая публикация (§139; бизнес-описание §4.3,
 * технический документ §16.14): одна вакансия, если ссылка совпадает без схемы, {@code www.}, регистра хоста и
 * {@code /} в конце (путь и параметры — с учётом регистра), или совпадают провайдер, хост ссылки и внешний id
 * (языковые разделы одной доски). Строится один раз на чтение (аудит §143); при нескольких совпадениях — вакансия с
 * наименьшим id.
 *
 * <p>Иначе — по названию и месту (решение владельца 2026-11-03, бизнес-описание §4.3, §260; {@link #byTitle}): то
 * же название и то же место, или место есть только у одной публикации; одна должность в разных местах — разные
 * вакансии; при сомнении — новая вакансия («лишняя запись лучше потерянной»). То же правило склеивает накопленные
 * дубли миграцией V92.</p>
 */
final class SameVacancy {

    private static final Pattern SCHEME = Pattern.compile("^(?i)https?://(www\\.)?");
    private static final Pattern TRAILING_SLASH = Pattern.compile("/+$");
    private static final String HOST_END = "/?#";

    private final Map<String, Long> byAddress = new HashMap<>();
    private final Map<String, Long> byExternalId = new HashMap<>();
    private final Map<String, List<Titled>> byTitle = new HashMap<>();

    private SameVacancy() {
    }

    /**
     * @param rows кандидаты: id вакансии, ссылка, провайдер, внешний id, ключ названия ({@code null} — не сравнивается),
     *             ключ места — по возрастанию id вакансии ({@link JobPostingRepository#findNeighbourPostings})
     * @return поиск по кандидатам
     */
    static SameVacancy of(List<Object[]> rows) {
        SameVacancy same = new SameVacancy();
        for (Object[] row : rows) {
            long vacancyId = ((Number) row[0]).longValue();
            String url = (String) row[1];
            same.byAddress.putIfAbsent(address(url), vacancyId);
            same.byExternalId.putIfAbsent(idKey((String) row[2], host(url), (String) row[3]), vacancyId);
            String title = (String) row[4];
            if (title != null && !title.isEmpty()) {
                same.byTitle.computeIfAbsent(title, key -> new ArrayList<>()).add(new Titled(vacancyId, (String) row[5]));
            }
        }
        return same;
    }

    /**
     * @return {@code true} — есть кандидаты для сравнения по названию: ключи новой публикации нужно получить
     */
    boolean hasTitles() {
        return !byTitle.isEmpty();
    }

    /**
     * Вакансия того же названия и места (§260). Место новой публикации есть — вакансия того же места; вакансии этого
     * названия только без места — первая из них; есть в других местах — нет (одна должность в разных местах). Места
     * нет — вакансия этого названия, если у работодателя оно не больше чем в одном месте; иначе нет: не понять, какая.
     *
     * @param title ключ названия новой публикации ({@link JobPostingRepository#sameTitle})
     * @param place ключ места новой публикации; пусто — места нет
     * @param taken вакансии, у которых уже есть публикация читаемого источника: одинаковые названия одного списка
     *              (5 × «Operátor výroby») — разные вакансии, к одной не присоединяются
     * @return вакансия с наименьшим id; пусто — новая вакансия
     */
    Optional<Long> byTitle(String title, String place, Set<Long> taken) {
        List<Titled> candidates = title == null || title.isEmpty() ? List.of() : byTitle.getOrDefault(title, List.of());
        long places = candidates.stream().map(Titled::place).filter(known -> !known.isEmpty()).distinct().count();
        boolean placed = place != null && !place.isEmpty();
        if (!placed && places > 1) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(candidate -> !taken.contains(candidate.vacancyId()))
                .filter(candidate -> !placed || candidate.place().equals(place) || places == 0)
                .map(Titled::vacancyId)
                .findFirst();
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

    /**
     * Кандидат по названию.
     *
     * @param vacancyId вакансия
     * @param place     ключ места её публикации; пусто — места нет
     */
    private record Titled(long vacancyId, String place) {
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
