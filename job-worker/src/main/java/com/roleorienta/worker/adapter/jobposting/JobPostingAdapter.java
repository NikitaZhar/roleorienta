package com.roleorienta.worker.adapter.jobposting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Адаптер разметки schema.org {@code JobPosting} на сайте компании (технический документ §5).
 * Доска — адрес кадровой страницы ({@code http}/{@code https}). С неё берутся только ссылки на
 * тот же хост; каждая такая страница читается, и первый блок JSON-LD
 * ({@code <script type="application/ld+json">}) с типом {@code JobPosting} даёт публикацию.
 * Страница без разметки — не вакансия и пропускается.
 * https://schema.org/JobPosting, https://developers.google.com/search/docs/appearance/structured-data/job-posting
 *
 * <p>Поля: {@code title}; {@code description} — текст; {@code jobLocation.address} — место
 * ({@code addressLocality}, {@code addressRegion}, {@code addressCountry}). Внешний id и ссылка —
 * адрес страницы без фрагмента.</p>
 *
 * <p>Полнота: отказ кадровой страницы — источник недоступен; временный отказ страницы вакансии или
 * ссылок больше потолка {@code maxPages} — неполное чтение (вакансии не закрываются). Постоянный
 * отказ страницы (404, запрет robots.txt и т.п.) — страница пропускается: повтор не поможет.</p>
 */
@Component
public class JobPostingAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "jobposting";

    private static final String TYPE = "JobPosting";

    private static final Set<String> SCHEMES = Set.of("http", "https");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = LoggerFactory.getLogger(JobPostingAdapter.class);

    private final ExternalHttpClient httpClient;
    private final JobPostingProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties потолок страниц
     */
    public JobPostingAdapter(ExternalHttpClient httpClient, JobPostingProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SourceReadResult read(String board) {
        URI careersPage = careersPage(board);
        if (careersPage == null) {
            return new SourceReadResult.Unavailable(new HttpResult.PermanentFailure(
                    HttpResult.Kind.BLOCKED, "Not a careers page URL: " + board));
        }
        HttpResult result = httpClient.get(careersPage);
        if (!(result instanceof HttpResult.Success success)) {
            return new SourceReadResult.Unavailable(result);
        }
        List<URI> links = sameHostLinks(success.body(), careersPage);
        boolean complete = links.size() <= properties.maxPages();
        if (!complete) {
            LOG.warn("Careers page {} links {} pages, over the ceiling of {}; read partially",
                    board, links.size(), properties.maxPages());
        }
        List<FetchedPosting> postings = new ArrayList<>();
        for (URI page : links.subList(0, Math.min(links.size(), properties.maxPages()))) {
            HttpResult pageResult = httpClient.get(page);
            if (pageResult instanceof HttpResult.Success pageSuccess) {
                FetchedPosting posting = posting(pageSuccess.body(), page.toString());
                if (posting != null) {
                    postings.add(posting);
                }
            } else if (pageResult instanceof HttpResult.TemporaryFailure) {
                LOG.warn("Page {} of careers page {} not read: {}", page, board, pageResult);
                complete = false;
            }
        }
        return new SourceReadResult.Read(postings, complete);
    }

    /**
     * @return адрес кадровой страницы без фрагмента; {@code null} — не абсолютный http(s)-адрес
     */
    private static URI careersPage(String board) {
        try {
            URI uri = new URI(board);
            if (uri.getScheme() == null || !SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null) {
                return null;
            }
            return withoutFragment(uri);
        } catch (URISyntaxException malformed) {
            return null;
        }
    }

    /**
     * Ссылки кадровой страницы на тот же хост, без фрагмента, без повторов и без самой кадровой
     * страницы, в порядке появления.
     */
    private static List<URI> sameHostLinks(String html, URI careersPage) {
        Document document = Jsoup.parse(html, careersPage.toString());
        Set<URI> links = new LinkedHashSet<>();
        for (Element anchor : document.select("a[href]")) {
            URI link = careersPage(anchor.absUrl("href"));
            if (link != null && careersPage.getHost().equalsIgnoreCase(link.getHost())
                    && !careersPage.equals(link)) {
                links.add(link);
            }
        }
        return new ArrayList<>(links);
    }

    private static URI withoutFragment(URI uri) throws URISyntaxException {
        String text = uri.toString();
        int fragment = text.indexOf('#');
        return new URI(fragment < 0 ? text : text.substring(0, fragment)).normalize();
    }

    /**
     * @return публикация из первого блока JSON-LD с {@code JobPosting} и непустым {@code title};
     *         {@code null} — такого блока нет
     */
    private static FetchedPosting posting(String html, String pageUrl) {
        for (Element script : Jsoup.parse(html, pageUrl).select("script[type=application/ld+json]")) {
            JsonNode node;
            try {
                node = jobPosting(JSON.readTree(script.data()));
            } catch (JsonProcessingException malformed) {
                LOG.warn("Malformed JSON-LD on {}: {}", pageUrl, malformed.getOriginalMessage());
                continue;
            }
            if (node != null && node.path("title").isTextual() && !node.path("title").asText().isBlank()) {
                return new FetchedPosting(pageUrl, node.path("title").asText().trim(), pageUrl,
                        location(node.path("jobLocation")), textOrNull(node.path("description")));
            }
        }
        return null;
    }

    /**
     * Ищет узел типа {@code JobPosting}: сам корень, элемент массива или элемент {@code @graph}.
     */
    private static JsonNode jobPosting(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            for (JsonNode item : root) {
                JsonNode found = jobPosting(item);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (isJobPosting(root.path("@type"))) {
            return root;
        }
        return root.has("@graph") ? jobPosting(root.get("@graph")) : null;
    }

    private static boolean isJobPosting(JsonNode type) {
        if (type.isArray()) {
            for (JsonNode item : type) {
                if (TYPE.equals(item.asText())) {
                    return true;
                }
            }
            return false;
        }
        return TYPE.equals(type.asText());
    }

    /**
     * Места работы: {@code jobLocation} — одно место или массив; адрес — строка или
     * {@code PostalAddress}. Места разделены «; », части адреса — «, ».
     *
     * @return места как указаны разметкой; {@code null} — не указаны
     */
    private static String location(JsonNode jobLocation) {
        StringJoiner places = new StringJoiner("; ");
        Iterable<JsonNode> placeNodes = jobLocation.isArray() ? jobLocation : List.of(jobLocation);
        for (JsonNode place : placeNodes) {
            JsonNode address = place.path("address");
            if (address.isTextual()) {
                addIfPresent(places, address.asText());
                continue;
            }
            StringJoiner parts = new StringJoiner(", ");
            addIfPresent(parts, address.path("addressLocality").asText(""));
            addIfPresent(parts, address.path("addressRegion").asText(""));
            JsonNode country = address.path("addressCountry");
            addIfPresent(parts, country.isObject() ? country.path("name").asText("") : country.asText(""));
            addIfPresent(places, parts.toString());
        }
        return places.length() == 0 ? null : places.toString();
    }

    private static void addIfPresent(StringJoiner joiner, String value) {
        if (!value.isBlank()) {
            joiner.add(value.trim());
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }
}
