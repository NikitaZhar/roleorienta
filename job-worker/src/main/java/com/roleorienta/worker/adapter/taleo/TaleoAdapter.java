package com.roleorienta.worker.adapter.taleo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.CountryNames;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Адаптер Oracle Taleo — кадровых разделов работодателей на {@code <компания>.taleo.net} (MOL Group / Slovnaft;
 * стенограмма §60, образцы {@code docs/samples/taleo-*}). robots.txt у Taleo нет (404).
 *
 * <ul>
 *   <li>Доска — хост и кадровый раздел: {@code molgroup.taleo.net/external}.</li>
 *   <li>Номер портала — со страницы поиска {@code /careersection/<раздел>/jobsearch.ftl}
 *       ({@code portal=<число>}).</li>
 *   <li>Список — {@code POST /careersection/rest/jobboard/searchjobs?lang=en&portal=<портал>}, тот же запрос, что
 *       делает страница поиска; без заголовков часового пояса ({@code tz}, {@code tzname}) Taleo отвечает 500, cookie
 *       не нужны.
 *       В ответе {@code requisitionList} по 25 ({@code contestNo} — номер вакансии, {@code column} — название и места),
 *       {@code pagingData.totalCount}. Страна сбора — фильтр {@code LOCATION} по id страны из фасетов первой страницы
 *       без фильтра (уровень 1, название страны); страны в фасетах нет — вакансий в ней нет.</li>
 *   <li>Внешний id — номер вакансии ({@code contestNo}); страница —
 *       {@code /careersection/<раздел>/jobdetail.ftl?job=<номер>}.</li>
 *   <li>Текст — страница вакансии: поле {@code initialHistory}, части {@code !|!}, текстовые блоки начинаются с
 *       {@code !*!} и закодированы как в адресе ({@code %3Cp%3E…}).</li>
 *   <li>Отказ первого запроса — источник недоступен; на следующих страницах, пустая страница раньше объявленного числа
 *       или упор в потолок — неполное чтение.</li>
 * </ul>
 */
@Component
public class TaleoAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "taleo";

    /** Доска: хост компании на taleo.net и кадровый раздел. */
    public static final Pattern BOARD = Pattern.compile("([a-z0-9-]+\\.taleo\\.net)/([a-z0-9_]+)");

    private static final Pattern PORTAL = Pattern.compile("portal=(\\d+)");
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern FACET_ID = Pattern.compile("\\d{1,20}");
    private static final String TEXT_BLOCK = "!*!";
    private static final String COUNTRY_LEVEL = "1";
    private static final Map<String, String> TIME_ZONE = Map.of("tz", "GMT+02:00", "tzname", "Europe/Bratislava");
    private static final String HOST_PLACEHOLDER = "{host}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(TaleoAdapter.class);

    private final ExternalHttpClient httpClient;
    private final TaleoProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес сайта и потолок страниц
     */
    public TaleoAdapter(ExternalHttpClient httpClient, TaleoProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SourceReadResult read(String board) {
        return read(board, null);
    }

    @Override
    public SourceReadResult read(String board, String country) {
        Matcher matcher = BOARD.matcher(board);
        if (!matcher.matches()) {
            return new SourceReadResult.Unavailable(new HttpResult.PermanentFailure(
                    HttpResult.Kind.BLOCKED, "Not a Taleo career section: " + board));
        }
        String section = careerSection(matcher);
        HttpResult searchPage = httpClient.get(URI.create(section + "/jobsearch.ftl"));
        if (!(searchPage instanceof HttpResult.Success page)) {
            return new SourceReadResult.Unavailable(searchPage);
        }
        Matcher portal = PORTAL.matcher(page.body());
        if (!portal.find()) {
            return malformed("no portal on the search page");
        }
        URI search = URI.create(site(matcher) + "/careersection/rest/jobboard/searchjobs?lang=en&portal="
                + portal.group(1));
        List<String> responses = new ArrayList<>();
        String location = null;
        if (country != null) {
            Optional<JsonNode> all = search(search, null, 1, responses);
            if (all.isEmpty()) {
                return malformed("no answer to the search");
            }
            Optional<String> countryId = countryFacet(all.get(), country);
            if (countryId.isEmpty()) {
                return SourceReadResult.Read.full(List.of(), responses);
            }
            location = countryId.get();
        }
        return readPages(matcher, search, location, responses);
    }

    private SourceReadResult readPages(Matcher board, URI search, String location, List<String> responses) {
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        int total = -1;
        int seen = 0;
        for (int page = 1; page <= properties.maxPages(); page++) {
            Optional<JsonNode> answer = search(search, location, page, responses);
            if (answer.isEmpty()) {
                if (page == 1) {
                    return malformed("no answer to the search");
                }
                LOG.warn("Taleo {} read partially at page {}", board.group(), page);
                return SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.PAGE_FAILED,
                        responses);
            }
            JsonNode jobs = answer.get().path("requisitionList");
            if (page == 1) {
                total = answer.get().path("pagingData").path("totalCount").asInt(-1);
            }
            for (JsonNode job : jobs) {
                posting(job, board).ifPresent(posting -> postings.putIfAbsent(posting.externalId(), posting));
            }
            seen += jobs.size();
            if (jobs.isEmpty() || total >= 0 && seen >= total) {
                return jobs.isEmpty() && total > seen
                        ? SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.LIST_ENDED_EARLY,
                                responses)
                        : SourceReadResult.Read.full(List.copyOf(postings.values()), responses);
            }
        }
        LOG.warn("Taleo {} exceeds {} pages, read partially", board.group(), properties.maxPages());
        return SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.PAGE_LIMIT, responses);
    }

    /**
     * Текст со страницы вакансии (поле {@code initialHistory}); место — из списка.
     */
    @Override
    public FetchedPosting detail(String board, String externalId) {
        Matcher matcher = BOARD.matcher(board);
        if (!matcher.matches() || !JOB_ID.matcher(externalId).matches()) {
            return null;
        }
        String url = jobUrl(matcher, externalId);
        if (!(httpClient.get(URI.create(url)) instanceof HttpResult.Success success)) {
            return null;
        }
        Element history = Jsoup.parse(success.body()).getElementById("initialHistory");
        String content = history == null ? null : description(history.attr("value"));
        if (content == null) {
            LOG.warn("Taleo posting {} on {}: no job description", externalId, board);
            return null;
        }
        return new FetchedPosting(externalId, null, url, null, content);
    }

    /**
     * Текстовые блоки поля {@code initialHistory}: части {@code !|!}, начинающиеся с {@code !*!}, раскодированные;
     * повторы (Taleo повторяет блок для второго языка) — один раз.
     *
     * @param history значение поля
     * @return HTML описания; {@code null} — блоков нет
     */
    static String description(String history) {
        Set<String> blocks = new LinkedHashSet<>();
        for (String part : history.split("!\\|!")) {
            if (part.startsWith(TEXT_BLOCK) && part.length() > TEXT_BLOCK.length()) {
                blocks.add(percentDecoded(part.substring(TEXT_BLOCK.length())));
            }
        }
        return blocks.isEmpty() ? null : String.join("\n", blocks);
    }

    /**
     * Раскодирует последовательности {@code %XX} (байты UTF-8); {@code %} без двух шестнадцатеричных цифр (в тексте
     * вакансии MOL — «%;m») и {@code +} остаются как есть.
     *
     * @param text закодированный блок
     * @return раскодированный текст
     */
    static String percentDecoded(String text) {
        StringBuilder decoded = new StringBuilder(text.length());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '%' && index + 2 < text.length()
                    && Character.digit(text.charAt(index + 1), 16) >= 0
                    && Character.digit(text.charAt(index + 2), 16) >= 0) {
                bytes.write(Integer.parseInt(text.substring(index + 1, index + 3), 16));
                index += 2;
                continue;
            }
            decoded.append(bytes.toString(StandardCharsets.UTF_8));
            bytes.reset();
            decoded.append(current);
        }
        return decoded.append(bytes.toString(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Страница поиска; отказ или ответ не JSON — пусто.
     */
    private Optional<JsonNode> search(URI search, String location, int page, List<String> responses) {
        String filter = location == null ? "" : "\"" + location + "\"";
        String body = "{\"multilineEnabled\":false,"
                + "\"sortingSelection\":{\"sortBySelectionParam\":\"3\",\"ascendingSortingOrder\":\"false\"},"
                + "\"fieldData\":{\"fields\":{\"KEYWORD\":\"\",\"LOCATION\":\"\"},\"valid\":true},"
                + "\"filterSelectionParam\":{\"searchFilterSelections\":[{\"id\":\"LOCATION\",\"selectedValues\":["
                + filter + "]}]},"
                + "\"advancedSearchFiltersSelectionParam\":{\"searchFilterSelections\":[]},\"pageNo\":" + page + "}";
        HttpResult result = httpClient.postJson(search, body, TIME_ZONE);
        if (!(result instanceof HttpResult.Success success)) {
            LOG.warn("Taleo search {} page {}: {}", search, page, result);
            return Optional.empty();
        }
        try {
            JsonNode answer = JSON.readTree(success.body());
            if (!answer.path("requisitionList").isArray()) {
                return Optional.empty();
            }
            responses.add(success.body());
            return Optional.of(answer);
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    /**
     * Id страны в фасете {@code LOCATION} (уровень 1 — страна).
     */
    static Optional<String> countryFacet(JsonNode answer, String country) {
        for (JsonNode facet : answer.path("facetResults")) {
            if (!"LOCATION".equals(facet.path("id").asText())) {
                continue;
            }
            for (JsonNode value : facet.path("facetValueResults")) {
                String id = value.path("id").asText();
                if (COUNTRY_LEVEL.equals(value.path("level").asText()) && FACET_ID.matcher(id).matches()
                        && CountryNames.isName(value.path("text").asText(), country)) {
                    return Optional.of(id);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Вакансия списка: номер, название ({@code column[0]}), места — колонки {@code locationsColumns} (JSON-массив
     * строкой, «["Slovakia-Bratislava"]»).
     */
    private Optional<FetchedPosting> posting(JsonNode job, Matcher board) {
        String id = job.path("contestNo").asText();
        JsonNode columns = job.path("column");
        if (!JOB_ID.matcher(id).matches() || !columns.isArray() || columns.isEmpty()) {
            LOG.warn("Taleo {}: posting without usable number skipped: {}", board.group(), id);
            return Optional.empty();
        }
        List<String> places = new ArrayList<>();
        for (JsonNode index : job.path("locationsColumns")) {
            places.addAll(places(columns.path(index.asInt()).asText()));
        }
        return Optional.of(new FetchedPosting(id, columns.path(0).asText().strip(), jobUrl(board, id),
                places.isEmpty() ? null : String.join("; ", places), null));
    }

    private static List<String> places(String column) {
        List<String> places = new ArrayList<>();
        try {
            for (JsonNode place : JSON.readTree(column)) {
                if (place.isTextual() && !place.asText().isBlank()) {
                    places.add(place.asText().strip());
                }
            }
        } catch (JsonProcessingException notJson) {
            if (!column.isBlank()) {
                places.add(column.strip());
            }
        }
        return places;
    }

    private String jobUrl(Matcher board, String id) {
        return careerSection(board) + "/jobdetail.ftl?job=" + id + "&lang=en";
    }

    private String careerSection(Matcher board) {
        return site(board) + "/careersection/" + board.group(2);
    }

    private String site(Matcher board) {
        return properties.siteUrl().replace(HOST_PLACEHOLDER, board.group(1));
    }

    private static SourceReadResult malformed(String reason) {
        return new SourceReadResult.Unavailable(
                new HttpResult.TemporaryFailure("Malformed Taleo answer: " + reason, Duration.ZERO));
    }
}
