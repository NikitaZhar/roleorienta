package com.roleorienta.worker.match;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Места публикаций → страны выполнения работы и формат работы (бизнес-описание §3, §4.4;
 * технический документ §6 «Нормализация»). Неизвестное остаётся неизвестным: без догадок.
 *
 * <ul>
 *   <li>Место делится на части по «;», «|», «/», « or »; часть — на сегменты по запятой. Сегмент —
 *       страна (название по-английски, по-словацки, по-немецки или известное сокращение), регион
 *       удалённой работы ({@link RemoteRegions}) или город (GeoNames: самый населённый с таким
 *       названием). Место Workday вида {@code IND.Pune}, {@code USA.VA.Reston} — страна по коду
 *       ISO 3166-1 alpha-3 до первой точки.</li>
 *   <li>Часть без распознанной страны («удалённо» без территории, «2 Locations», неизвестный город)
 *       — страна неясна.</li>
 *   <li>Формат: гибрид и удалённо — по названию, местам и тексту; офис — только по названию и местам
 *       (в тексте шаблонные фразы вроде «in the office or in the field» офиса не означают), но офис в
 *       тексте рядом с удалённой работой даёт противоречие («on-site role, work from home
 *       occasionally»). Гибрид важнее прочего («hybrid, 2 days remote»); ничего — не указан.</li>
 * </ul>
 */
final class LocationResolver {

    /** Код страны ISO 3166-1 alpha-3 в начале места Workday: {@code IND.Pune}. */
    private static final Pattern ISO3_PREFIX = Pattern.compile("^([A-Z]{3})\\.");
    private static final Pattern PLACES = Pattern.compile("[;|/]| or ");
    private static final Pattern REMOTE_WORDS = Pattern.compile(
            "\\b(fully remote|remote first|remote|work from home|home office|praca z domu|na dialku)\\b");
    private static final Pattern REMOTE_TEXT = Pattern.compile(
            "\\b(fully remote|100% remote|remote position|remote work|work from home|praca z domu|praca na dialku)\\b");
    private static final Pattern HYBRID = Pattern.compile("\\bhybrid\\w*\\b");
    private static final Pattern OFFICE = Pattern.compile(
            "\\b(on site|onsite|in office|office based|in the office|v kancelarii|prezencne)\\b");
    private static final Map<String, String> ALIASES = Map.of("usa", "US", "us", "US", "uk", "GB",
            "england", "GB", "great britain", "GB", "czechia", "CZ", "slovak republic", "SK", "holland", "NL",
            "south korea", "KR", "uae", "AE");

    private final Map<String, String> countriesByName;
    private final Map<String, String> countriesByIso3;
    private final Map<String, String> citiesByName;
    private final RemoteRegions regions;

    /**
     * @param citiesByName страна города по нормализованному названию (GeoNames)
     * @param regions      регионы удалённой работы
     */
    LocationResolver(Map<String, String> citiesByName, RemoteRegions regions) {
        this.citiesByName = citiesByName;
        this.regions = regions;
        Map<String, String> names = new HashMap<>(ALIASES);
        Map<String, String> iso3 = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            Locale country = Locale.of("", code);
            iso3.put(country.getISO3Country(), code);
            for (Locale language : List.of(Locale.ENGLISH, Locale.GERMAN, Locale.of("sk"))) {
                names.putIfAbsent(PositionDictionary.normalize(country.getDisplayCountry(language)).strip(), code);
            }
        }
        this.countriesByName = Map.copyOf(names);
        this.countriesByIso3 = Map.copyOf(iso3);
    }

    /**
     * @param locations места публикаций вакансии ({@code null} — не указано)
     * @param title     название вакансии
     * @param text      текст вакансии
     * @return страны, неясность страны и формат
     */
    LocationFacts resolve(List<String> locations, String title, String text) {
        Set<String> countries = new HashSet<>();
        boolean uncertain = false;
        for (String location : locations) {
            if (location == null || location.isBlank()) {
                continue;
            }
            for (String place : PLACES.split(location)) {
                Set<String> placeCountries = place(place);
                countries.addAll(placeCountries);
                uncertain |= placeCountries.isEmpty() && !place.isBlank();
            }
        }
        String markers = PositionDictionary.normalize(title + " " + String.join(" ", locations.stream()
                .map(location -> location == null ? "" : location).toList()));
        return new LocationFacts(Set.copyOf(countries), uncertain,
                format(markers, PositionDictionary.normalize(text)));
    }

    private Set<String> place(String place) {
        Set<String> countries = new HashSet<>();
        for (String segment : place.split(",")) {
            Matcher iso3 = ISO3_PREFIX.matcher(segment.strip());
            if (iso3.find() && countriesByIso3.containsKey(iso3.group(1))) {
                countries.add(countriesByIso3.get(iso3.group(1)));
                continue;
            }
            String name = REMOTE_WORDS.matcher(PositionDictionary.normalize(segment)).replaceAll(" ")
                    .replaceAll("\\s+", " ").strip();
            if (name.isEmpty()) {
                continue;
            }
            String country = countriesByName.get(name);
            Set<String> region = regions.countries(name);
            if (country != null) {
                countries.add(country);
            } else if (region != null) {
                countries.addAll(region);
            } else if (citiesByName.containsKey(name)) {
                countries.add(citiesByName.get(name));
            }
        }
        return countries;
    }

    private static WorkFormat format(String titleAndLocations, String text) {
        if (HYBRID.matcher(titleAndLocations).find() || HYBRID.matcher(text).find()) {
            return WorkFormat.HYBRID;
        }
        boolean remote = REMOTE_WORDS.matcher(titleAndLocations).find() || REMOTE_TEXT.matcher(text).find();
        boolean office = OFFICE.matcher(titleAndLocations).find();
        if (remote && (office || OFFICE.matcher(text).find())) {
            return WorkFormat.CONFLICT;
        }
        return remote ? WorkFormat.REMOTE : office ? WorkFormat.OFFICE : null;
    }

    /**
     * @param countries страны выполнения работы; «*» — без ограничения
     * @param uncertain есть место без ясной страны
     * @param format    формат; {@code null} — не указан
     */
    record LocationFacts(Set<String> countries, boolean uncertain, WorkFormat format) {
    }
}
