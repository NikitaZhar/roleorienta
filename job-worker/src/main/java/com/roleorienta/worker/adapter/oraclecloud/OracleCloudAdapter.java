package com.roleorienta.worker.adapter.oraclecloud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.PostedDates;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Адаптер Oracle Recruiting Cloud (Oracle Fusion HCM, «Candidate Experience») — кадровых сайтов работодателей на
 * {@code <код>.fa.<зона>.oraclecloud.com} (Carmeuse, Emerson, Garrett Motion, Vertiv; стенограмма §117, образцы
 * {@code docs/samples/oracle-*.json}).
 *
 * <ul>
 *   <li>Доска — хост сайта и номер сайта: {@code ekiz.fa.em2.oraclecloud.com/CX} (номер — как в адресе
 *       {@code …/hcmUI/CandidateExperience/<язык>/sites/<номер>}, регистр сохраняется).</li>
 *   <li>Список — публичный REST-запрос, тот же, что делает страница сайта, без входа и ключа:
 *       {@code GET /hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true
 *       &expand=requisitionList.secondaryLocations&finder=findReqs;siteNumber=<номер>,location=<страна
 *       по-английски>,limit=25,offset=N,sortBy=POSTING_DATES_DESC}. В ответе {@code items[0]}: {@code TotalJobsCount}
 *       и {@code requisitionList} — {@code Id}, {@code Title}, {@code PrimaryLocation} («ZÁBORSKÉ, PREŠOV,
 *       Slovakia»), {@code PrimaryLocationCountry}, {@code secondaryLocations[].CountryCode}. Страницы — сдвигом
 *       {@code offset} до {@code TotalJobsCount}.</li>
 *   <li>Страна сбора: в список входят вакансии, у которых страна — основное место или одно из дополнительных (поиск
 *       по месту на замере точен, но проверка — как у других адаптеров).</li>
 *   <li>Внешний id — {@code Id} вакансии; адрес — {@code /hcmUI/CandidateExperience/en/sites/<номер>/job/<Id>}.</li>
 *   <li>Текст — {@code recruitingCEJobRequisitionDetails?expand=all&onlyData=true&finder=ById;Id="<Id>",
 *       siteNumber=<номер>}: {@code ExternalDescriptionStr}, {@code ExternalResponsibilitiesStr},
 *       {@code ExternalQualificationsStr} (HTML). Все пусты — текста нет.</li>
 *   <li>robots.txt у сайтов нет (404 на замере §117).</li>
 *   <li>Отказ или ответ не список на первой странице — источник недоступен, вакансии не закрываются; на следующих,
 *       пустая страница раньше объявленного числа или упор в потолок — неполное чтение.</li>
 * </ul>
 */
@Component
public class OracleCloudAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "oraclecloud";

    /** Доска: хост сайта Oracle и номер сайта. */
    public static final Pattern BOARD = Pattern.compile(
            "([a-z0-9-]+\\.fa\\.[a-z0-9-]+\\.oraclecloud\\.com)/([A-Za-z0-9_]+)");

    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final int PAGE_SIZE = 25;
    private static final String API = "/hcmRestApi/resources/latest/";
    private static final List<String> TEXT_FIELDS = List.of("ExternalDescriptionStr", "ExternalResponsibilitiesStr",
            "ExternalQualificationsStr");
    private static final String HOST_PLACEHOLDER = "{host}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(OracleCloudAdapter.class);

    private final ExternalHttpClient httpClient;
    private final OracleCloudProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес сайта и потолок страниц
     */
    public OracleCloudAdapter(ExternalHttpClient httpClient, OracleCloudProperties properties) {
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
                    HttpResult.Kind.BLOCKED, "Not an Oracle Recruiting Cloud site: " + board));
        }
        String location = country == null ? "" : ",location=" + URLEncoder.encode(
                Locale.of("", country).getDisplayCountry(Locale.ENGLISH), StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        List<String> responses = new ArrayList<>();
        PostedDates dates = new PostedDates();
        int offset = 0;
        for (int page = 0; page < properties.maxPages(); page++) {
            HttpResult result = httpClient.get(URI.create(site(matcher) + API
                    + "recruitingCEJobRequisitions?onlyData=true&expand=requisitionList.secondaryLocations"
                    + "&finder=findReqs;siteNumber=" + matcher.group(2) + location + ",limit=" + PAGE_SIZE
                    + ",offset=" + offset + ",sortBy=POSTING_DATES_DESC"));
            JsonNode list = result instanceof HttpResult.Success success ? list(success.body()) : null;
            if (list == null) {
                HttpResult failure = result instanceof HttpResult.Success ? new HttpResult.TemporaryFailure(
                        "Oracle Recruiting Cloud answer is not a job list: " + board, Duration.ZERO) : result;
                if (page == 0) {
                    return new SourceReadResult.Unavailable(failure);
                }
                LOG.warn("Oracle Recruiting Cloud {} read partially at offset {}: {}", board, offset, failure);
                return dates.attach(SourceReadResult.Read.partial(List.copyOf(postings.values()),
                        PartialReason.PAGE_FAILED, responses));
            }
            responses.add(((HttpResult.Success) result).body());
            int total = list.get("TotalJobsCount").asInt();
            JsonNode requisitions = list.path("requisitionList");
            for (JsonNode requisition : requisitions) {
                posting(requisition, matcher, country).ifPresent(posting -> {
                    postings.putIfAbsent(posting.externalId(), posting);
                    dates.put(posting.externalId(), PostedDates.parse(requisition.path("PostedDate").asText(null)));
                });
            }
            offset += requisitions.size();
            if (requisitions.isEmpty() || offset >= total) {
                return requisitions.isEmpty() && total > offset
                        ? dates.attach(SourceReadResult.Read.partial(List.copyOf(postings.values()),
                                PartialReason.LIST_ENDED_EARLY, responses))
                        : dates.attach(SourceReadResult.Read.full(List.copyOf(postings.values()), responses));
            }
        }
        LOG.warn("Oracle Recruiting Cloud {} exceeds {} pages, read partially", board, properties.maxPages());
        return dates.attach(
                SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.PAGE_LIMIT, responses));
    }

    /**
     * Текст вакансии отдельным запросом; место — из списка.
     */
    @Override
    public FetchedPosting detail(String board, String externalId) {
        Matcher matcher = BOARD.matcher(board);
        if (!matcher.matches() || !JOB_ID.matcher(externalId).matches()) {
            return null;
        }
        HttpResult result = httpClient.get(URI.create(site(matcher) + API
                + "recruitingCEJobRequisitionDetails?expand=all&onlyData=true&finder=ById;Id=%22" + externalId
                + "%22,siteNumber=" + matcher.group(2)));
        JsonNode item = result instanceof HttpResult.Success success ? first(success.body()) : null;
        StringBuilder content = new StringBuilder();
        if (item != null) {
            TEXT_FIELDS.stream().map(field -> item.path(field).asText(""))
                    .filter(text -> !text.isBlank()).forEach(content::append);
        }
        if (content.isEmpty()) {
            LOG.warn("Oracle Recruiting Cloud posting {} on {}: no job description", externalId, board);
            return null;
        }
        return new FetchedPosting(externalId, null, jobUrl(matcher, externalId), null, content.toString());
    }

    /**
     * Вакансия списка, если она в стране сбора (основное место или одно из дополнительных); без {@code Id} или с
     * недопустимым {@code Id} — пусто.
     */
    private Optional<FetchedPosting> posting(JsonNode requisition, Matcher board, String country) {
        String id = requisition.path("Id").asText("");
        if (!JOB_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        if (country != null && !inCountry(requisition, country)) {
            return Optional.empty();
        }
        String place = requisition.path("PrimaryLocation").asText("").strip();
        return Optional.of(new FetchedPosting(id, requisition.path("Title").asText(""), jobUrl(board, id),
                place.isEmpty() ? null : place, null));
    }

    private static boolean inCountry(JsonNode requisition, String country) {
        if (country.equals(requisition.path("PrimaryLocationCountry").asText(""))) {
            return true;
        }
        for (JsonNode secondary : requisition.path("secondaryLocations")) {
            if (country.equals(secondary.path("CountryCode").asText(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ответ списка: {@code items[0]} с {@code TotalJobsCount}; иначе (не JSON, ошибка сайта) — {@code null}.
     */
    private static JsonNode list(String body) {
        JsonNode item = first(body);
        return item != null && item.has("TotalJobsCount") ? item : null;
    }

    private static JsonNode first(String body) {
        try {
            JsonNode items = JSON.readTree(body).path("items");
            return items.isArray() && !items.isEmpty() ? items.get(0) : null;
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }

    private String jobUrl(Matcher board, String id) {
        return site(board) + "/hcmUI/CandidateExperience/en/sites/" + board.group(2) + "/job/" + id;
    }

    private String site(Matcher board) {
        return properties.siteUrl().replace(HOST_PLACEHOLDER, board.group(1));
    }
}
