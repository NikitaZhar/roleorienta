package com.roleorienta.worker.adapter.jobposting;

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
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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
 * <p>Полнота: отказ кадровой страницы — источник недоступен; временный отказ страницы вакансии — неполное
 * чтение (вакансии не закрываются). Постоянный отказ страницы (404, запрет robots.txt и т.п.) — страница
 * пропускается: повтор не поможет.</p>
 *
 * <p>Окно по кругу (решение владельца 2026-10-15, аудит §121). Ссылок больше потолка {@code maxPages} — за одно
 * чтение читаются {@code maxPages} ссылок подряд по кругу, начиная с номера, который сдвигается на
 * {@code maxPages} каждые сутки (UTC); источник читается раз в сутки, поэтому за {@code ⌈ссылок / maxPages⌉} суток
 * прочитаны все. Список ссылок кадровой страницы при этом получен целиком, поэтому чтение полное, а ссылки вне
 * окна отдаются как «не прочитанные в этот раз» ({@link SourceReadResult.Read#notRead()}): их известные
 * вакансии не меняются (не подтверждаются, не теряют подтверждение, отсутствие не засчитывается) — и при
 * временном отказе страницы окна (чтение тогда неполное, как без окна); вакансия, чья ссылка пропала с кадровой
 * страницы, засчитывается отсутствующей, как при обычном полном чтении. Чтение одного источника укладывается в срок
 * задания (100 страниц — минуты, а не полчаса).</p>
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
    private final Clock clock;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties потолок страниц
     * @param clock      часы: сутки задают, с какой ссылки начинается чтение сверх потолка
     */
    public JobPostingAdapter(ExternalHttpClient httpClient, JobPostingProperties properties, Clock clock) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.clock = clock;
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
        List<URI> pages = todaysPages(links);
        Set<String> notRead = new HashSet<>();
        links.forEach(link -> notRead.add(link.toString()));
        pages.forEach(page -> notRead.remove(page.toString()));
        if (!notRead.isEmpty()) {
            LOG.info("Careers page {} links {} pages, over the ceiling of {}; {} read today, the rest on next days",
                    board, links.size(), properties.maxPages(), pages.size());
        }
        PartialReason partialReason = null;
        List<FetchedPosting> postings = new ArrayList<>();
        List<String> responses = new ArrayList<>(List.of(success.body()));
        PostedDates dates = new PostedDates();
        for (URI page : pages) {
            HttpResult pageResult = httpClient.get(page);
            if (pageResult instanceof HttpResult.Success pageSuccess) {
                responses.add(pageSuccess.body());
                FetchedPosting posting = posting(pageSuccess.body(), page.toString(), dates);
                if (posting != null) {
                    postings.add(posting);
                }
            } else if (pageResult instanceof HttpResult.TemporaryFailure) {
                LOG.warn("Page {} of careers page {} not read: {}", page, board, pageResult);
                if (partialReason == null) {
                    partialReason = PartialReason.PAGE_FAILED;
                }
            }
        }
        return dates.attach(new SourceReadResult.Read(postings, partialReason, responses, Set.copyOf(notRead)));
    }

    /**
     * Ссылки этого чтения: все, если их не больше потолка; иначе {@code maxPages} подряд по кругу с номера
     * {@code (сутки UTC × maxPages) mod число ссылок}.
     */
    private List<URI> todaysPages(List<URI> links) {
        int max = properties.maxPages();
        if (links.size() <= max) {
            return links;
        }
        long day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toEpochDay();
        int start = (int) Math.floorMod(day * max, (long) links.size());
        List<URI> pages = new ArrayList<>(max);
        for (int index = 0; index < max; index++) {
            pages.add(links.get((start + index) % links.size()));
        }
        return pages;
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
     * @return публикация из первого блока JSON-LD с {@code JobPosting} и непустым {@code title} (его
     *         {@code datePosted} — в {@code dates}, §127); {@code null} — такого блока нет
     */
    private static FetchedPosting posting(String html, String pageUrl, PostedDates dates) {
        for (Element script : Jsoup.parse(html, pageUrl).select("script[type=application/ld+json]")) {
            JsonNode node;
            try {
                node = jobPosting(JSON.readTree(script.data()));
            } catch (JsonProcessingException malformed) {
                LOG.warn("Malformed JSON-LD on {}: {}", pageUrl, malformed.getOriginalMessage());
                continue;
            }
            if (node != null && node.path("title").isTextual() && !node.path("title").asText().isBlank()) {
                dates.put(pageUrl, PostedDates.parse(node.path("datePosted").asText(null)));
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
