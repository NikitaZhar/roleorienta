package com.roleorienta.worker.match;

import java.util.Arrays;
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
 *   <li>Место делится на части по «;», «|», «/», « or »; часть — на сегменты по запятой, сегмент — на куски по
 *       тире с пробелами и скобкам ({@code Cairo - ETIC}, {@code Manila (One Ayala Tower 2)}, стенограмма §149).
 *       Кусок — страна (название по-английски, по-словацки, по-немецки или известное сокращение), регион
 *       удалённой работы ({@link RemoteRegions}) или город (GeoNames: самый населённый с таким
 *       названием); не распознан целиком — пробуется его начало из меньшего числа слов, не короче
 *       {@value #MIN_PREFIX} букв ({@code Bengaluru Millenia}, {@code Bratislava-Staré Mesto}). Место Workday вида
 *       {@code IND.Pune}, {@code USA.VA.Reston} — страна по коду ISO 3166-1 alpha-3 до первой точки.</li>
 *   <li>Часть без распознанной страны («удалённо» без территории, «2 Locations», неизвестный город) стран
 *       не добавляет; у вакансии без единой страны страна не определена (выдача — с пометкой, §148).</li>
 *   <li>Формат: гибрид и удалённо — по названию, местам и тексту; офис — только по названию и местам
 *       (в тексте шаблонные фразы вроде «in the office or in the field» офиса не означают), но офис в
 *       тексте рядом с удалённой работой даёт противоречие («on-site role, work from home
 *       occasionally»). Гибрид важнее прочего («hybrid, 2 days remote»); ничего — не указан.</li>
 * </ul>
 */
final class LocationResolver {

    /**
     * Версия правил распознавания мест — входит в версию сопоставления: её смена пересчитывает страны и формат всех
     * незакрытых вакансий ({@code MatchVacanciesHandler}). Коротко: длина всей версии ограничена
     * {@code MatchVacanciesHandler.MAX_VERSION_LENGTH} («places-2» дал 41 символ и запись отказала, стенограмма §149;
     * запас проверяет {@code MatchVersionTests}).
     */
    static final String VERSION = "p2";

    /** Самое короткое начало куска места, которое ищется как город или страна: короче — слишком много совпадений. */
    static final int MIN_PREFIX = 4;

    /** Код страны ISO 3166-1 alpha-3 в начале места Workday: {@code IND.Pune}. */
    private static final Pattern ISO3_PREFIX = Pattern.compile("^([A-Z]{3})\\.");
    private static final Pattern PLACES = Pattern.compile("[;|/]| or ");
    /** Куски сегмента: тире с пробелами вокруг и скобки (тире без пробелов — часть названия: Baden-Baden). */
    private static final Pattern PIECES = Pattern.compile("\\s+\\p{Pd}\\s+|[()]");
    private static final Pattern REMOTE_WORDS = Pattern.compile(
            "\\b(fully remote|remote first|remote|work from home|home office|praca z domu|na dialku)\\b");
    private static final Pattern REMOTE_TEXT = Pattern.compile(
            "\\b(fully remote|100% remote|remote position|remote work|work from home|praca z domu|praca na dialku)\\b");
    private static final Pattern HYBRID = Pattern.compile("\\bhybrid\\w*\\b");
    private static final Pattern OFFICE = Pattern.compile(
            "\\b(on site|onsite|in office|office based|in the office|v kancelarii|prezencne)\\b");
    private static final Map<String, String> ALIASES = Map.of("usa", "US", "us", "US", "uk", "GB",
            "england", "GB", "great britain", "GB", "czech republic", "CZ", "slovak republic", "SK", "holland", "NL",
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
     * @return страны и формат
     */
    LocationFacts resolve(List<String> locations, String title, String text) {
        Set<String> countries = new HashSet<>();
        for (String location : locations) {
            if (location == null || location.isBlank()) {
                continue;
            }
            for (String place : PLACES.split(location)) {
                countries.addAll(place(place));
            }
        }
        String markers = PositionDictionary.normalize(title + " " + String.join(" ", locations.stream()
                .map(location -> location == null ? "" : location).toList()));
        return new LocationFacts(Set.copyOf(countries), format(markers, PositionDictionary.normalize(text)));
    }

    private Set<String> place(String place) {
        Set<String> countries = new HashSet<>();
        for (String segment : place.split(",")) {
            for (String piece : PIECES.split(segment)) {
                countries.addAll(piece(piece));
            }
        }
        return countries;
    }

    /** Страны куска места: код Workday, название целиком или его начало (см. описание класса). */
    private Set<String> piece(String piece) {
        Matcher iso3 = ISO3_PREFIX.matcher(piece.strip());
        if (iso3.find() && countriesByIso3.containsKey(iso3.group(1))) {
            return Set.of(countriesByIso3.get(iso3.group(1)));
        }
        String name = REMOTE_WORDS.matcher(PositionDictionary.normalize(piece)).replaceAll(" ")
                .replaceAll("\\s+", " ").strip();
        if (name.isEmpty()) {
            return Set.of();
        }
        Set<String> whole = named(name);
        if (!whole.isEmpty()) {
            return whole;
        }
        String[] words = name.split(" ");
        for (int count = words.length - 1; count > 0; count--) {
            String start = String.join(" ", Arrays.copyOf(words, count));
            Set<String> found = start.length() < MIN_PREFIX ? Set.of() : named(start);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return Set.of();
    }

    /** Страна, регион удалённой работы или город с таким нормализованным названием; нет — пусто. */
    private Set<String> named(String name) {
        String country = countriesByName.get(name);
        if (country != null) {
            return Set.of(country);
        }
        Set<String> region = regions.countries(name);
        if (region != null) {
            return region;
        }
        String city = citiesByName.get(name);
        return city == null ? Set.of() : Set.of(city);
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
     * @param countries страны выполнения работы; «*» — без ограничения; пусто — страна не определена
     * @param format    формат; {@code null} — не указан
     */
    record LocationFacts(Set<String> countries, WorkFormat format) {

        /**
         * Страна государственного портала, если места страны не дали (решение владельца 2026-10-20, стенограмма
         * §149): портал национальный — его вакансии в его стране; чужая страна в месте (объявления EURES) распознаётся
         * и правило не срабатывает.
         *
         * @param portalCountry страна портала, с которого пришла вакансия; {@code null} — вакансии портала нет
         * @return сведения со страной портала; иначе — эти же сведения
         */
        LocationFacts orPortalCountry(String portalCountry) {
            return countries.isEmpty() && portalCountry != null
                    ? new LocationFacts(Set.of(portalCountry), format) : this;
        }
    }
}
