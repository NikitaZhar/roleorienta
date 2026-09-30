package com.roleorienta.worker.adapter.workday;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.PostingCheck;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Адаптер Workday: список вакансий витрины
 * {@code POST https://<тенант>.<dc>.myworkdayjobs.com/wday/cxs/<тенант>/<сайт>/jobs} страницами
 * {@code offset/limit}. Поля публикации — {@code title}, {@code externalPath} (внешний id и ссылка),
 * {@code locationsText}; {@code total} приходит только на первой странице. Текста в списке нет —
 * он читается деталью ({@link #detail}). У публикации в нескольких местах {@code locationsText} —
 * сводка «2 Locations»: место в списке считается не полученным, места берутся из детали.
 *
 * <p>Доска — {@code <тенант>.<dc>.myworkdayjobs.com/<сайт>}; другой хост не читается (отказ
 * {@code BLOCKED}). Отказ на первой странице — источник недоступен; на следующих, пустая страница
 * раньше {@code total} или упор в потолок страниц — неполное чтение (вакансии не закрываются).
 * Endpoint не документирован как публичный контракт (технический документ §5).</p>
 *
 * <p>Фильтр по стране (технический документ §5): первый ответ без фильтра содержит фасеты —
 * группы значений для отбора ({@code facets}: {@code facetParameter}, {@code values} с
 * {@code descriptor} и {@code id}; значение может нести вложенный фасет). Имя параметра страны у
 * тенантов разное, поэтому берётся фасет, в имени которого есть {@code country}, и значение с
 * английским названием страны (или официальным: «Slovak Republic»); дальше список читается с
 * {@code appliedFacets}. Тот же фасет отвечает, есть ли у доски публикации в стране
 * ({@link #hasPostingsIn}) — одним запросом. Фасета страны
 * нет — список читается без фильтра; страны нет среди значений — у доски нет её публикаций.
 * Пропажа из отфильтрованного списка проверяется деталью публикации ({@link #check}).</p>
 */
@Component
public class WorkdayAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "workday";

    private static final Pattern BOARD = Pattern.compile(
            "(([a-z0-9-]+)\\.wd\\d+\\.myworkdayjobs\\.com)/([A-Za-z0-9_-]+)");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Сводка списка вместо мест: «2 Locations». */
    private static final Pattern LOCATIONS_SUMMARY = Pattern.compile("\\d+ Locations");

    /** Название страны в фасете, если тенант пишет не краткое английское: «Slovak Republic». */
    private static final Map<String, String> OFFICIAL_NAMES = Map.of("SK", "Slovak Republic", "CZ", "Czech Republic");

    /** {@code appliedFacets} без фильтра. */
    private static final String NO_FACETS = "{}";

    private static final Logger LOG = LoggerFactory.getLogger(WorkdayAdapter.class);

    private final ExternalHttpClient httpClient;
    private final WorkdayProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес витрины и размер страниц
     */
    public WorkdayAdapter(ExternalHttpClient httpClient, WorkdayProperties properties) {
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
                    HttpResult.Kind.BLOCKED, "Not a Workday board: " + board));
        }
        if (country == null) {
            return readPages(matcher, NO_FACETS);
        }
        HttpResult result = httpClient.postJson(URI.create(apiBase(matcher) + "/jobs"), requestBody(NO_FACETS, 0));
        JsonNode json = result instanceof HttpResult.Success success ? readJson(success.body()) : null;
        if (json == null) {
            return new SourceReadResult.Unavailable(result instanceof HttpResult.Success
                    ? new HttpResult.TemporaryFailure("Malformed Workday response", Duration.ZERO) : result);
        }
        CountryFilter filter = countryFilter(json, country);
        if (!filter.facetFound()) {
            LOG.warn("Workday board {} has no country facet, read without filter", board);
            return readPages(matcher, NO_FACETS);
        }
        if (filter.appliedFacets() != null) {
            return readPages(matcher, filter.appliedFacets());
        }
        LOG.info("Workday board {} has no postings in {}", board, country);
        return SourceReadResult.Read.full(List.of(), List.of(((HttpResult.Success) result).body()));
    }

    /**
     * Первый ответ без фильтра: есть фасет страны — ответ по его значениям; нет фасета или ответа —
     * пусто.
     */
    @Override
    public Optional<Boolean> hasPostingsIn(String board, String country) {
        Matcher matcher = BOARD.matcher(board);
        if (!matcher.matches()) {
            return Optional.of(false);
        }
        HttpResult result = httpClient.postJson(URI.create(apiBase(matcher) + "/jobs"), requestBody(NO_FACETS, 0));
        JsonNode json = result instanceof HttpResult.Success success ? readJson(success.body()) : null;
        if (json == null) {
            return Optional.empty();
        }
        CountryFilter filter = countryFilter(json, country);
        return filter.facetFound() ? Optional.of(filter.appliedFacets() != null) : Optional.empty();
    }

    /**
     * Фильтр по стране из фасетов первого ответа.
     */
    private static CountryFilter countryFilter(JsonNode firstPage, String country) {
        String countryName = Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
        String officialName = OFFICIAL_NAMES.get(country);
        List<JsonNode> countryFacets = new ArrayList<>();
        collectCountryFacets(firstPage.path("facets"), countryFacets);
        if (countryFacets.isEmpty()) {
            return new CountryFilter(false, null);
        }
        for (JsonNode facet : countryFacets) {
            for (JsonNode value : facet.path("values")) {
                String descriptor = value.path("descriptor").asText();
                if (countryName.equalsIgnoreCase(descriptor) || descriptor.equalsIgnoreCase(officialName)) {
                    return new CountryFilter(true, "{" + JSON.getNodeFactory().textNode(
                            facet.path("facetParameter").asText()) + ":[" + JSON.getNodeFactory().textNode(
                            value.path("id").asText()) + "]}");
                }
            }
        }
        return new CountryFilter(true, null);
    }

    /**
     * @param facetFound    у доски есть фасет страны
     * @param appliedFacets {@code appliedFacets} со значением страны; {@code null} — публикаций в
     *                      стране нет (или нет фасета)
     */
    private record CountryFilter(boolean facetFound, String appliedFacets) {
    }

    /**
     * Список страницами с заданными {@code appliedFacets}.
     */
    private SourceReadResult readPages(Matcher matcher, String appliedFacets) {
        String board = matcher.group(0);
        URI uri = URI.create(apiBase(matcher) + "/jobs");
        List<FetchedPosting> postings = new ArrayList<>();
        List<String> responses = new ArrayList<>();
        int total = 0;
        for (int page = 0; page < properties.maxPages(); page++) {
            int offset = page * properties.pageSize();
            HttpResult result = httpClient.postJson(uri, requestBody(appliedFacets, offset));
            JsonNode json = result instanceof HttpResult.Success success ? readJson(success.body()) : null;
            if (json == null) {
                HttpResult failure = result instanceof HttpResult.Success
                        ? new HttpResult.TemporaryFailure("Malformed Workday response", Duration.ZERO) : result;
                if (page == 0) {
                    return new SourceReadResult.Unavailable(failure);
                }
                LOG.warn("Workday board {} read partially at offset {}: {}", board, offset, failure);
                return SourceReadResult.Read.partial(postings, PartialReason.PAGE_FAILED, responses);
            }
            responses.add(((HttpResult.Success) result).body());
            if (page == 0) {
                total = json.path("total").asInt();
            }
            JsonNode jobs = json.path("jobPostings");
            for (JsonNode job : jobs) {
                String path = job.path("externalPath").asText();
                String location = textOrNull(job.path("locationsText"));
                if (location != null && LOCATIONS_SUMMARY.matcher(location).matches()) {
                    location = null;
                }
                postings.add(new FetchedPosting(path, job.path("title").asText(), postingUrl(matcher, path),
                        location, null));
            }
            if (offset + jobs.size() >= total) {
                return SourceReadResult.Read.full(postings, responses);
            }
            if (jobs.isEmpty()) {
                return SourceReadResult.Read.partial(postings, PartialReason.LIST_ENDED_EARLY, responses);
            }
        }
        LOG.warn("Workday board {} exceeds {} pages, read partially", board, properties.maxPages());
        return SourceReadResult.Read.partial(postings, PartialReason.PAGE_LIMIT, responses);
    }

    /**
     * Собирает фасеты страны: имя параметра содержит {@code country}; обходит и вложенные фасеты
     * значений.
     */
    private static void collectCountryFacets(JsonNode facets, List<JsonNode> found) {
        for (JsonNode facet : facets) {
            if (facet.path("facetParameter").asText().toLowerCase(Locale.ROOT).contains("country")) {
                found.add(facet);
            }
            collectCountryFacets(facet.path("values"), found);
        }
    }

    /**
     * Деталь публикации: {@code GET <api>/<externalPath>}; места — {@code jobPostingInfo.location} и
     * {@code additionalLocations}, текст — {@code jobDescription}.
     */
    @Override
    public FetchedPosting detail(String board, String externalId) {
        Matcher matcher = BOARD.matcher(board);
        HttpResult result = requestDetail(matcher, externalId);
        JsonNode info = result instanceof HttpResult.Success success ? jobPostingInfo(success.body()) : null;
        if (info == null || !info.path("jobDescription").isTextual()) {
            LOG.warn("Workday posting {} on {}: no jobDescription", externalId, board);
        }
        return info == null ? null : posting(matcher, externalId, info);
    }

    /**
     * Деталь публикации: {@code jobPostingInfo} есть — публикация на месте (сведения обновятся);
     * 404/410 — её нет; прочее — проверить не удалось.
     */
    @Override
    public PostingCheck check(String board, String externalId) {
        Matcher matcher = BOARD.matcher(board);
        HttpResult result = requestDetail(matcher, externalId);
        if (result instanceof HttpResult.PermanentFailure failure && failure.kind() == HttpResult.Kind.NOT_FOUND) {
            return new PostingCheck.Absent();
        }
        JsonNode info = result instanceof HttpResult.Success success ? jobPostingInfo(success.body()) : null;
        if (info == null || !info.path("title").isTextual()) {
            return new PostingCheck.Unknown("Workday posting " + externalId + " not checked: " + result);
        }
        return new PostingCheck.Present(posting(matcher, externalId, info));
    }

    private FetchedPosting posting(Matcher matcher, String externalId, JsonNode info) {
        return new FetchedPosting(externalId, info.path("title").asText(), postingUrl(matcher, externalId),
                locations(info), textOrNull(info.path("jobDescription")));
    }

    /**
     * Основное и дополнительные места детали через «; »; ни одного — {@code null}.
     */
    private static String locations(JsonNode info) {
        List<String> places = new ArrayList<>();
        if (info.path("location").isTextual()) {
            places.add(info.path("location").asText());
        }
        for (JsonNode place : info.path("additionalLocations")) {
            if (place.isTextual()) {
                places.add(place.asText());
            }
        }
        return places.isEmpty() ? null : String.join("; ", places);
    }

    /**
     * {@code GET <api>/<externalPath>}; чужая доска или путь не {@code /job/…} — запрос не выполняется.
     */
    private HttpResult requestDetail(Matcher matcher, String externalId) {
        if (!matcher.matches() || !externalId.startsWith("/job/")) {
            return new HttpResult.PermanentFailure(HttpResult.Kind.BLOCKED, "Not a Workday posting: " + externalId);
        }
        try {
            return httpClient.get(URI.create(apiBase(matcher) + externalId));
        } catch (IllegalArgumentException malformedPath) {
            return new HttpResult.PermanentFailure(HttpResult.Kind.BLOCKED, "Malformed path: " + externalId);
        }
    }

    private static JsonNode jobPostingInfo(String body) {
        try {
            JsonNode info = JSON.readTree(body).path("jobPostingInfo");
            return info.isObject() ? info : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String postingUrl(Matcher matcher, String externalPath) {
        return properties.baseUrlTemplate().replace("{host}", matcher.group(1)) + "/" + matcher.group(3)
                + externalPath;
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private String apiBase(Matcher board) {
        return properties.baseUrlTemplate().replace("{host}", board.group(1))
                + "/wday/cxs/" + board.group(2) + "/" + board.group(3);
    }

    private String requestBody(String appliedFacets, int offset) {
        return "{\"appliedFacets\":" + appliedFacets + ",\"limit\":" + properties.pageSize() + ",\"offset\":"
                + offset + ",\"searchText\":\"\"}";
    }

    private static JsonNode readJson(String body) {
        try {
            JsonNode json = JSON.readTree(body);
            return json != null && json.path("jobPostings").isArray() ? json : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
