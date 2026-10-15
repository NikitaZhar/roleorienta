package com.roleorienta.worker.adapter.successfactors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roleorienta.worker.adapter.CountryNames;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Адаптер SAP SuccessFactors Career Site Builder — кадровых сайтов работодателей на SuccessFactors
 * ({@code jobs.zf.com}, {@code jobs.kaufland.com}, {@code kariera.lidl.sk}; стенограмма §57, §115, образцы
 * {@code docs/samples/sf-*.html}, {@code sf-*.json}).
 *
 * <ul>
 *   <li>Доска — хост кадрового сайта в нижнем регистре.</li>
 *   <li>Первый запрос — страница поиска {@code /search/?q=&locationsearch=<страна по-английски>&startrow=0}. По ней
 *       видно, как сайт отдаёт список — одним из трёх видов:
 *     <ul>
 *       <li>строки {@code tr.data-row}: ссылка {@code a.jobTitle-link} — {@code /job/<название>/<id>/}, место
 *           {@code span.jobLocation} — «Levice, NI, SK, 934 01»;</li>
 *       <li>плитки {@code li.job-tile} (§115): та же ссылка, место — поля плитки: {@code location}
 *           («TRNAVA, SK, 91701»), иначе {@code city}, {@code state}, {@code country} через запятую;</li>
 *       <li>новый поиск SAP (виджет {@code xweb/rmk-jobs-search}, вакансий в HTML нет; §115): список — тот же
 *           запрос, что делает виджет, {@code POST /services/recruiting/v1/jobs} с языком страницы
 *           ({@code appParams.locale}), страной ({@code location}) и номером страницы ({@code pageNumber}, по 10).
 *           Сайт требует токен страницы ({@code var CSRFToken}) в заголовке {@code X-CSRF-Token} и cookie её сессии
 *           — всё чтение идёт в одной сессии cookie HTTP-клиента ({@code ExternalHttpClient.CookieSession}, аудит
 *           §121), после чтения она выбрасывается. В ответе
 *           {@code totalJobs} и {@code jobSearchResult[].response}: {@code id}, {@code urlTitle},
 *           {@code unifiedStandardTitle}, место {@code jobLocationShort} — «Vrbove, SVK». Путь вакансии —
 *           {@code /job/<urlTitle>/<id>-<язык>/}. Ответ без {@code totalJobs} или с {@code error} — отказ.</li>
 *     </ul></li>
 *   <li>Число вакансий у строк и плиток — последнее число в {@code .paginationLabel} («1 – 25 of 36») или
 *       {@code #tile-search-results-label} («Showing 1 to 15 of 15 Jobs»); страницы — сдвигом {@code startrow} на
 *       число строк страницы.</li>
 *   <li>Поиску по месту сайтов нельзя доверять полностью, поэтому с фильтром страны в список входят только вакансии,
 *       где место называет страну: код страны отдельным полем (два или три знака: «SK», «SVK») или её название.
 *       Исключение — плитка без полей места и страны (у Schaeffler только город): страну ей подтверждает только поиск
 *       сайта, она входит (решение владельца, §115).</li>
 *   <li>Внешний id — путь вакансии, как в ссылке списка.</li>
 *   <li>Текст — страница вакансии: {@code span.jobdescription} (микроразметка {@code itemprop=description}).</li>
 *   <li>Ответ «ничего не найдено» ({@code #noresults}; ниже сайт показывает последние вакансии вне поиска — у
 *       Kaufland вакансии Германии, образец {@code sf-kaufland.html}) — вакансий нет, полное пустое чтение (аудит
 *       §66).</li>
 *   <li>Отказ на первой странице или первая страница без списка, без числа вакансий и без ответа «ничего не
 *       найдено» (заглушка, проверка на бота, смена разметки; аудит §65) — источник недоступен, вакансии не
 *       закрываются; на следующих, пустая страница раньше объявленного числа
 *       или упор в потолок — неполное чтение (вакансии не закрываются).</li>
 * </ul>
 */
@Component
public class SuccessFactorsAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "successfactors";

    private static final Pattern BOARD = Pattern.compile("[a-z0-9-]+(?:\\.[a-z0-9-]+)+");
    private static final Pattern JOB_PATH = Pattern.compile("(?:/[^/?#]+)?/job/[^/?#]+/\\d+(?:-[a-z]{2}_[A-Z]{2})?/?");
    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");
    private static final Pattern COUNTRY_CODE3 = Pattern.compile("[A-Z]{3}");
    /** Трёхбуквенные коды стран (ISO 3166-1 alpha-3): «SVK» в месте нового поиска SAP. */
    private static final Set<String> ALPHA3 = Arrays.stream(Locale.getISOCountries())
            .map(code -> Locale.of("", code).getISO3Country()).collect(Collectors.toUnmodifiableSet());
    private static final Pattern TOTAL = Pattern.compile("(\\d[\\d\\s.,]*\\d|\\d)\\D*$");
    private static final Pattern CSRF_TOKEN = Pattern.compile("var CSRFToken = \"([^\"]+)\"");
    private static final Pattern PAGE_LOCALE = Pattern.compile("locale:\\s*\"([a-z]{2}_[A-Z]{2})\"");
    /** Виджет нового поиска SAP на странице поиска. */
    private static final String JOBS_SEARCH_WIDGET = "xweb/rmk-jobs-search";
    private static final String JOBS_SEARCH_PATH = "/services/recruiting/v1/jobs";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    /** Конец строки места в ответе нового поиска («Skalica, SVK, 90901<br/>»). */
    private static final String LINE_BREAK = "<br/>";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_EXTERNAL_ID = 200;
    private static final String HOST_PLACEHOLDER = "{host}";
    /** Блок ответа «по запросу ничего не найдено». */
    private static final String NO_RESULTS = "noresults";
    /** Поля плитки с местом: само место или его части — по порядку (§115). */
    private static final String PLACE_FIELD = "location";
    private static final List<String> PLACE_PARTS = List.of("city", "state", "country");
    private static final Logger LOG = LoggerFactory.getLogger(SuccessFactorsAdapter.class);

    private final ExternalHttpClient httpClient;
    private final SuccessFactorsProperties properties;

    /**
     * Страница списка строк или плиток.
     *
     * @param rows     вакансии страницы
     * @param unplaced внешние id плиток без полей места и страны
     * @param total    объявленное число вакансий; {@code -1} — не объявлено
     */
    private record ListPage(List<FetchedPosting> rows, Set<String> unplaced, int total) {
    }

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес сайта и потолок страниц
     */
    public SuccessFactorsAdapter(ExternalHttpClient httpClient, SuccessFactorsProperties properties) {
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
                    HttpResult.Kind.BLOCKED, "Not a SuccessFactors career site: " + board));
        }
        ExternalHttpClient.CookieSession session = httpClient.cookieSession();
        HttpResult first = session.get(searchUri(board, place(country), 0));
        if (!(first instanceof HttpResult.Success success)) {
            return new SourceReadResult.Unavailable(first);
        }
        List<String> responses = new ArrayList<>(List.of(success.body()));
        Document document = Jsoup.parse(success.body());
        if (document.getElementById(NO_RESULTS) != null) {
            return SourceReadResult.Read.full(List.of(), responses);
        }
        if (success.body().contains(JOBS_SEARCH_WIDGET)) {
            return readJobsSearch(session, board, country, success.body(), responses);
        }
        ListPage page = listPage(document, board);
        if (page.total() < 0 && page.rows().isEmpty()) {
            return new SourceReadResult.Unavailable(new HttpResult.TemporaryFailure(
                    "SuccessFactors page is not a result list: " + board, Duration.ZERO));
        }
        return readListPages(session, board, country, page, responses);
    }

    /**
     * @return название страны сбора по-английски для поиска сайта; страна не задана — пустая строка (без фильтра)
     */
    private static String place(String country) {
        return country == null ? "" : Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
    }

    /**
     * Строки или плитки: первая страница уже прочитана, следующие — сдвигом {@code startrow}.
     */
    private SourceReadResult readListPages(ExternalHttpClient.CookieSession session, String board, String country,
            ListPage first, List<String> responses) {
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        Set<String> unplaced = new HashSet<>();
        int total = first.total();
        int offset = 0;
        ListPage page = first;
        for (int index = 0; index < properties.maxPages(); index++) {
            if (index > 0) {
                HttpResult result = session.get(searchUri(board, place(country), offset));
                if (!(result instanceof HttpResult.Success success)) {
                    LOG.warn("SuccessFactors {} read partially at row {}: {}", board, offset, result);
                    return SourceReadResult.Read.partial(filter(postings, unplaced, country),
                            PartialReason.PAGE_FAILED, responses);
                }
                responses.add(success.body());
                page = listPage(Jsoup.parse(success.body()), board);
            }
            page.rows().forEach(posting -> postings.putIfAbsent(posting.externalId(), posting));
            unplaced.addAll(page.unplaced());
            offset += page.rows().size();
            if (page.rows().isEmpty() || total >= 0 && offset >= total) {
                return page.rows().isEmpty() && total > offset
                        ? SourceReadResult.Read.partial(filter(postings, unplaced, country),
                                PartialReason.LIST_ENDED_EARLY, responses)
                        : SourceReadResult.Read.full(filter(postings, unplaced, country), responses);
            }
        }
        LOG.warn("SuccessFactors {} exceeds {} pages, read partially", board, properties.maxPages());
        return SourceReadResult.Read.partial(filter(postings, unplaced, country), PartialReason.PAGE_LIMIT,
                responses);
    }

    /**
     * Новый поиск SAP: страницы ответа JSON с номера 0, пока не набрано {@code totalJobs}. Нет токена или языка на
     * странице — источник временно недоступен (смена разметки), вакансии не закрываются.
     */
    private SourceReadResult readJobsSearch(ExternalHttpClient.CookieSession session, String board, String country,
            String pageBody, List<String> responses) {
        Matcher token = CSRF_TOKEN.matcher(pageBody);
        Matcher locale = PAGE_LOCALE.matcher(pageBody);
        if (!token.find() || !locale.find()) {
            return new SourceReadResult.Unavailable(new HttpResult.TemporaryFailure(
                    "SuccessFactors job search without token or locale: " + board, Duration.ZERO));
        }
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        int offset = 0;
        for (int page = 0; page < properties.maxPages(); page++) {
            HttpResult result = session.postJson(URI.create(site(board) + JOBS_SEARCH_PATH),
                    searchBody(locale.group(1), place(country), page), Map.of(CSRF_HEADER, token.group(1)));
            JsonNode answer = result instanceof HttpResult.Success success ? answer(success.body()) : null;
            if (answer == null) {
                HttpResult failure = result instanceof HttpResult.Success ? new HttpResult.TemporaryFailure(
                        "SuccessFactors job search answer is not a job list: " + board, Duration.ZERO) : result;
                if (page == 0) {
                    return new SourceReadResult.Unavailable(failure);
                }
                LOG.warn("SuccessFactors {} job search read partially at page {}: {}", board, page, failure);
                return SourceReadResult.Read.partial(filter(postings, Set.of(), country), PartialReason.PAGE_FAILED,
                        responses);
            }
            responses.add(((HttpResult.Success) result).body());
            int total = answer.get("totalJobs").asInt();
            List<FetchedPosting> rows = jobs(answer.path("jobSearchResult"), board, locale.group(1));
            rows.forEach(posting -> postings.putIfAbsent(posting.externalId(), posting));
            int listed = answer.path("jobSearchResult").size();
            offset += listed;
            if (listed == 0 || offset >= total) {
                return listed == 0 && total > offset
                        ? SourceReadResult.Read.partial(filter(postings, Set.of(), country),
                                PartialReason.LIST_ENDED_EARLY, responses)
                        : SourceReadResult.Read.full(filter(postings, Set.of(), country), responses);
            }
        }
        LOG.warn("SuccessFactors {} exceeds {} job search pages, read partially", board, properties.maxPages());
        return SourceReadResult.Read.partial(filter(postings, Set.of(), country), PartialReason.PAGE_LIMIT, responses);
    }

    /**
     * Текст со страницы вакансии ({@code span.jobdescription}); место — из списка.
     */
    @Override
    public FetchedPosting detail(String board, String externalId) {
        if (!BOARD.matcher(board).matches() || !JOB_PATH.matcher(externalId).matches()) {
            return null;
        }
        String url = site(board) + externalId;
        Optional<URI> uri = uri(url);
        if (uri.isEmpty() || !(httpClient.get(uri.get()) instanceof HttpResult.Success success)) {
            return null;
        }
        Element description = Jsoup.parse(success.body()).selectFirst(".jobdescription, [itemprop=description]");
        if (description == null) {
            LOG.warn("SuccessFactors posting {} on {}: no job description", externalId, board);
            return null;
        }
        return new FetchedPosting(externalId, null, url, null, description.html());
    }

    /**
     * Страница строк ({@code tr.data-row}) или плиток ({@code li.job-tile}); путь длиннее поля внешнего id — строка
     * пропускается.
     */
    private ListPage listPage(Document page, String board) {
        List<FetchedPosting> rows = new ArrayList<>();
        Set<String> unplaced = new HashSet<>();
        for (Element row : page.select("tr.data-row")) {
            Element place = row.selectFirst("span.jobLocation");
            posting(row, board, place == null || place.text().isBlank() ? null : place.text().strip())
                    .ifPresent(rows::add);
        }
        for (Element tile : page.select("li.job-tile")) {
            Optional<FetchedPosting> posting = posting(tile, board, tilePlace(tile));
            posting.ifPresent(rows::add);
            if (posting.isPresent() && tileField(tile, PLACE_FIELD) == null && tileField(tile, "country") == null) {
                unplaced.add(posting.get().externalId());
            }
        }
        return new ListPage(rows, unplaced, declaredTotal(page));
    }

    /**
     * Вакансия строки или плитки по ссылке {@code a.jobTitle-link}; нет ссылки или путь не вакансии — пусто.
     */
    private Optional<FetchedPosting> posting(Element row, String board, String place) {
        Element link = row.selectFirst("a.jobTitle-link[href]");
        if (link == null) {
            return Optional.empty();
        }
        String path = link.attr("href").replaceFirst("^[A-Za-z]+://[^/]+", "");
        return job(board, path, link.text(), place);
    }

    /**
     * Вакансия по пути: путь не вакансии, длиннее поля внешнего id или не адрес — пусто (пишется в журнал).
     */
    private Optional<FetchedPosting> job(String board, String path, String title, String place) {
        if (!JOB_PATH.matcher(path).matches() || path.length() > MAX_EXTERNAL_ID || uri(site(board) + path).isEmpty()) {
            LOG.warn("SuccessFactors {}: posting path skipped: {}", board, path);
            return Optional.empty();
        }
        return Optional.of(new FetchedPosting(path, title, site(board) + path, place, null));
    }

    /**
     * Место плитки: поле {@code location}, иначе {@code city}, {@code state}, {@code country} через запятую; полей
     * нет — {@code null}.
     */
    private static String tilePlace(Element tile) {
        String location = tileField(tile, PLACE_FIELD);
        if (location != null) {
            return location;
        }
        List<String> parts = PLACE_PARTS.stream().map(field -> tileField(tile, field)).filter(Objects::nonNull)
                .toList();
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * Значение поля плитки (версия для широкого экрана; та же плитка повторена для планшета и телефона); пустое —
     * {@code null}.
     */
    private static String tileField(Element tile, String field) {
        Element value = tile.selectFirst(".sub-section-desktop .section-field." + field + " [id$=-value]");
        return value == null || value.text().isBlank() ? null : value.text().strip();
    }

    /**
     * Вакансии ответа нового поиска SAP; запись без {@code id} или {@code urlTitle} пропускается.
     */
    private List<FetchedPosting> jobs(JsonNode results, String board, String locale) {
        List<FetchedPosting> jobs = new ArrayList<>();
        for (JsonNode result : results) {
            JsonNode response = result.path("response");
            String id = response.path("id").asText("");
            String urlTitle = response.path("urlTitle").asText("");
            if (id.isEmpty() || urlTitle.isEmpty()) {
                continue;
            }
            JsonNode places = response.path("jobLocationShort");
            String place = places.isArray() && !places.isEmpty()
                    ? places.get(0).asText().replace(LINE_BREAK, "").strip() : "";
            job(board, "/job/" + urlTitle + "/" + id + "-" + locale + "/",
                    response.path("unifiedStandardTitle").asText(""),
                    place.isEmpty() ? null : place).ifPresent(jobs::add);
        }
        return jobs;
    }

    /**
     * Ответ нового поиска SAP — список: есть {@code totalJobs}, нет {@code error}; иначе (не JSON, ошибка сайта)
     * — {@code null}.
     */
    private static JsonNode answer(String body) {
        try {
            JsonNode answer = JSON.readTree(body);
            return answer != null && answer.has("totalJobs") && !answer.has("error") ? answer : null;
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }

    /**
     * Тело запроса нового поиска SAP — как у виджета страницы ({@code appParams}).
     */
    private static String searchBody(String locale, String place, int page) {
        ObjectNode body = JSON.createObjectNode().put("locale", locale).put("pageNumber", page).put("sortBy", "")
                .put("keywords", "").put("location", place).put("brand", "").put("categoryId", 0).put("alertId", "")
                .put("rcmCandidateId", "");
        body.putObject("facetFilters");
        body.putArray("skills");
        return body.toString();
    }

    private URI searchUri(String board, String place, int offset) {
        return URI.create(site(board) + "/search/?q=&locationsearch=" + URLEncoder.encode(place, StandardCharsets.UTF_8)
                + "&startrow=" + offset);
    }

    /**
     * Объявленное число вакансий — последнее число в {@code .paginationLabel} («Results 1 – 25 of 36») или
     * {@code #tile-search-results-label} («Showing 1 to 15 of 15 Jobs»); нет или не число ({@code int} не вмещает)
     * — {@code -1} (список читается, пока страницы не пустые; адаптер не бросает).
     */
    private static int declaredTotal(Document page) {
        Element label = page.selectFirst(".paginationLabel, #tile-search-results-label");
        Matcher total = TOTAL.matcher(label == null ? "" : label.text());
        if (!total.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(total.group(1).replaceAll("\\D", ""));
        } catch (NumberFormatException tooLong) {
            return -1;
        }
    }

    /**
     * Место называет страну: код страны — последнее поле из двух заглавных букв («Levice, NI, SK, 934 01»; в
     * «Regina, SK, CA, S4P 3Y2» SK — провинция, страна CA) или из трёх, если это код страны ISO 3166-1 alpha-3
     * («Vrbove, SVK», §115), — или название страны.
     */
    static boolean inCountry(String location, String country) {
        if (location == null) {
            return false;
        }
        String code = null;
        for (String part : location.split(",")) {
            String field = part.strip();
            if (COUNTRY_CODE.matcher(field).matches()) {
                code = field;
            } else if (COUNTRY_CODE3.matcher(field).matches() && ALPHA3.contains(field)) {
                code = field.equals(Locale.of("", country).getISO3Country()) ? country : field;
            }
        }
        return code != null ? code.equals(country) : CountryNames.mentions(location, country);
    }

    /**
     * Вакансии страны: место называет страну ({@link #inCountry}) или вакансия — плитка без полей места и страны.
     */
    private static List<FetchedPosting> filter(Map<String, FetchedPosting> postings, Set<String> unplaced,
            String country) {
        return postings.values().stream().filter(posting -> country == null
                || unplaced.contains(posting.externalId()) || inCountry(posting.location(), country)).toList();
    }

    /**
     * Адрес из строки страницы; недопустимый (пробел, «%» без кода в пути вакансии) — пусто, без исключения.
     */
    private static Optional<URI> uri(String url) {
        try {
            return Optional.of(new URI(url));
        } catch (URISyntaxException invalid) {
            return Optional.empty();
        }
    }

    private String site(String board) {
        return properties.siteUrl().replace(HOST_PLACEHOLDER, board);
    }
}
