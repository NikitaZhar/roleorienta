package com.roleorienta.worker.adapter.phenom;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.roleorienta.worker.adapter.CountryNames;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.io.IOException;
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
 * Адаптер Phenom — кадровых сайтов работодателей на платформе Phenom ({@code careers.dhl.com/eu/sk},
 * {@code careers.allianz.com/global/en}; стенограмма §59, образцы {@code docs/samples/phenom-*.html}). Страницы сайта
 * несут свои данные в скрипте {@code phApp.ddo = {…};} — из него и читает адаптер (robots.txt сайтов закрывает
 * {@code apply}, {@code chatbot} и т. п., но не поиск и не страницы вакансий).
 *
 * <ul>
 *   <li>Доска — хост и путь языка сайта в нижнем регистре ({@code careers.dhl.com/eu/sk}).</li>
 *   <li>Список — {@code <доска>/search-results?qcountry=<страна по-английски>&from=N}: в данных
 *       {@code eagerLoadRefineSearch} — {@code totalHits} и по 10 вакансий в {@code data.jobs} ({@code jobId},
 *       {@code title}, {@code location}, {@code country}). Фильтр страны держится на всех страницах; в список входят
 *       только вакансии, где страна названа ({@code country} или место).</li>
 *   <li>Внешний id — {@code jobId} ({@code AV-366620}); страница вакансии — {@code <доска>/job/<jobId>}.</li>
 *   <li>Текст — страница вакансии, данные {@code jobDetail.data.job.description} (HTML).</li>
 *   <li>Отказ или нет данных на первой странице — источник недоступен; на следующих, пустая страница раньше
 *       объявленного числа или упор в потолок — неполное чтение (вакансии не закрываются).</li>
 * </ul>
 */
@Component
public class PhenomAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "phenom";

    /** Доска: хост и необязательный путь языка ({@code careers.dhl.com/eu/sk}). */
    public static final Pattern BOARD = Pattern.compile("[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/[a-z0-9_-]+)*");

    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");
    private static final Pattern DATA = Pattern.compile("phApp\\.ddo\\s*=\\s*");
    private static final String BOARD_PLACEHOLDER = "{board}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(PhenomAdapter.class);

    private final ExternalHttpClient httpClient;
    private final PhenomProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес сайта и потолок страниц
     */
    public PhenomAdapter(ExternalHttpClient httpClient, PhenomProperties properties) {
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
        if (!BOARD.matcher(board).matches()) {
            return new SourceReadResult.Unavailable(new HttpResult.PermanentFailure(
                    HttpResult.Kind.BLOCKED, "Not a Phenom career site: " + board));
        }
        String filter = country == null ? "" : "qcountry=" + URLEncoder.encode(
                Locale.of("", country).getDisplayCountry(Locale.ENGLISH), StandardCharsets.UTF_8) + "&";
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        List<String> responses = new ArrayList<>();
        int total = -1;
        int offset = 0;
        for (int page = 0; page < properties.maxPages(); page++) {
            HttpResult result = httpClient.get(
                    URI.create(site(board) + "/search-results?" + filter + "from=" + offset));
            JsonNode search = result instanceof HttpResult.Success success
                    ? data(success.body()).path("eagerLoadRefineSearch") : MissingNode.getInstance();
            JsonNode jobs = search.path("data").path("jobs");
            if (!jobs.isArray()) {
                if (page == 0) {
                    return new SourceReadResult.Unavailable(result instanceof HttpResult.Success
                            ? new HttpResult.TemporaryFailure("Malformed Phenom page: no job list", Duration.ZERO)
                            : result);
                }
                LOG.warn("Phenom {} read partially at {}: {}", board, offset, result);
                return SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.PAGE_FAILED,
                        responses);
            }
            responses.add(((HttpResult.Success) result).body());
            if (page == 0) {
                total = search.path("totalHits").asInt(-1);
            }
            for (JsonNode job : jobs) {
                posting(job, board, country)
                        .ifPresent(posting -> postings.putIfAbsent(posting.externalId(), posting));
            }
            offset += jobs.size();
            if (jobs.isEmpty() || total >= 0 && offset >= total) {
                return jobs.isEmpty() && total > offset
                        ? SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.LIST_ENDED_EARLY,
                                responses)
                        : SourceReadResult.Read.full(List.copyOf(postings.values()), responses);
            }
        }
        LOG.warn("Phenom {} exceeds {} pages, read partially", board, properties.maxPages());
        return SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.PAGE_LIMIT, responses);
    }

    /**
     * Текст и место со страницы вакансии ({@code jobDetail.data.job}).
     */
    @Override
    public FetchedPosting detail(String board, String externalId) {
        if (!BOARD.matcher(board).matches() || !JOB_ID.matcher(externalId).matches()) {
            return null;
        }
        String url = jobUrl(board, externalId);
        if (!(httpClient.get(URI.create(url)) instanceof HttpResult.Success success)) {
            return null;
        }
        JsonNode job = data(success.body()).path("jobDetail").path("data").path("job");
        String description = text(job.path("description"));
        if (description == null) {
            LOG.warn("Phenom posting {} on {}: no job description", externalId, board);
            return null;
        }
        return new FetchedPosting(externalId, null, url, text(job.path("location")), description);
    }

    /**
     * Вакансия списка; без годного {@code jobId} или в другой стране (при фильтре) — пусто.
     */
    private Optional<FetchedPosting> posting(JsonNode job, String board, String country) {
        String id = job.path("jobId").asText();
        String location = text(job.path("location"));
        if (!JOB_ID.matcher(id).matches()) {
            LOG.warn("Phenom {}: posting without usable jobId skipped: {}", board, id);
            return Optional.empty();
        }
        if (country != null && !CountryNames.isName(job.path("country").asText(), country)
                && (location == null || !CountryNames.mentions(location, country))) {
            return Optional.empty();
        }
        return Optional.of(new FetchedPosting(id, job.path("title").asText(), jobUrl(board, id), location,
                null));
    }

    /**
     * Данные страницы — объект после {@code phApp.ddo =}; нет или не разобран — пустой узел.
     *
     * @param html страница сайта Phenom
     * @return данные страницы
     */
    static JsonNode data(String html) {
        Matcher start = DATA.matcher(html);
        if (!start.find()) {
            return MissingNode.getInstance();
        }
        try (JsonParser parser = JSON.getFactory().createParser(html.substring(start.end()))) {
            JsonNode data = JSON.readTree(parser);
            return data == null ? MissingNode.getInstance() : data;
        } catch (IOException malformed) {
            return MissingNode.getInstance();
        }
    }

    private String jobUrl(String board, String id) {
        return site(board) + "/job/" + id;
    }

    private String site(String board) {
        return properties.siteUrl().replace(BOARD_PLACEHOLDER, board);
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().strip() : null;
    }
}
