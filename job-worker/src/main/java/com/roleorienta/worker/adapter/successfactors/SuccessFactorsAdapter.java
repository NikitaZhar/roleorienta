package com.roleorienta.worker.adapter.successfactors;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Адаптер SAP SuccessFactors Career Site Builder — кадровых сайтов работодателей на SuccessFactors
 * ({@code jobs.zf.com}, {@code jobs.kaufland.com}, {@code kariera.lidl.sk}; стенограмма §57, образцы
 * {@code docs/samples/sf-*.html}).
 *
 * <ul>
 *   <li>Доска — хост кадрового сайта в нижнем регистре.</li>
 *   <li>Список — {@code /search/?q=&locationsearch=<страна по-английски>&startrow=N}: строки
 *       {@code tr.data-row}, ссылка {@code a.jobTitle-link} — {@code /job/<название>/<id>/}, место
 *       {@code span.jobLocation} — «Levice, NI, SK, 934 01». Число вакансий — в {@code .paginationLabel}
 *       («1 – 25 of 36»), страницы — сдвигом {@code startrow} на число строк страницы. Поиску по месту
 *       сайтов нельзя доверять полностью, поэтому с фильтром страны в список входят только строки, где место
 *       называет страну: код страны отдельным полем или её название.</li>
 *   <li>Внешний id — путь вакансии {@code /job/<название>/<id>/}, как в ссылке списка.</li>
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
    private static final Pattern JOB_PATH = Pattern.compile("(?:/[^/?#]+)?/job/[^/?#]+/\\d+/?");
    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");
    private static final Pattern TOTAL = Pattern.compile("(\\d[\\d\\s.,]*)\\s*$");
    private static final int MAX_EXTERNAL_ID = 200;
    private static final String HOST_PLACEHOLDER = "{host}";
    /** Блок ответа «по запросу ничего не найдено». */
    private static final String NO_RESULTS = "noresults";
    private static final Logger LOG = LoggerFactory.getLogger(SuccessFactorsAdapter.class);

    private final ExternalHttpClient httpClient;
    private final SuccessFactorsProperties properties;

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
        String search = country == null ? "" : URLEncoder.encode(
                Locale.of("", country).getDisplayCountry(Locale.ENGLISH), StandardCharsets.UTF_8);
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        List<String> responses = new ArrayList<>();
        int total = -1;
        int offset = 0;
        for (int page = 0; page < properties.maxPages(); page++) {
            HttpResult result = httpClient.get(URI.create(site(board) + "/search/?q=&locationsearch=" + search
                    + "&startrow=" + offset));
            if (!(result instanceof HttpResult.Success success)) {
                if (page == 0) {
                    return new SourceReadResult.Unavailable(result);
                }
                LOG.warn("SuccessFactors {} read partially at row {}: {}", board, offset, result);
                return SourceReadResult.Read.partial(filter(postings, country), PartialReason.PAGE_FAILED, responses);
            }
            responses.add(success.body());
            Document document = Jsoup.parse(success.body());
            if (page == 0 && document.getElementById(NO_RESULTS) != null) {
                return SourceReadResult.Read.full(List.of(), responses);
            }
            if (page == 0) {
                total = declaredTotal(document);
            }
            List<FetchedPosting> rows = rows(document, board);
            if (page == 0 && total < 0 && rows.isEmpty()) {
                return new SourceReadResult.Unavailable(new HttpResult.TemporaryFailure(
                        "SuccessFactors page is not a result list: " + board, Duration.ZERO));
            }
            rows.forEach(posting -> postings.putIfAbsent(posting.externalId(), posting));
            offset += rows.size();
            if (rows.isEmpty() || total >= 0 && offset >= total) {
                return rows.isEmpty() && total > offset
                        ? SourceReadResult.Read.partial(filter(postings, country), PartialReason.LIST_ENDED_EARLY,
                                responses)
                        : SourceReadResult.Read.full(filter(postings, country), responses);
            }
        }
        LOG.warn("SuccessFactors {} exceeds {} pages, read partially", board, properties.maxPages());
        return SourceReadResult.Read.partial(filter(postings, country), PartialReason.PAGE_LIMIT, responses);
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
     * Строки страницы списка; путь длиннее поля внешнего id — строка пропускается.
     */
    private List<FetchedPosting> rows(Document page, String board) {
        List<FetchedPosting> rows = new ArrayList<>();
        for (Element row : page.select("tr.data-row")) {
            Element link = row.selectFirst("a.jobTitle-link[href]");
            Element place = row.selectFirst("span.jobLocation");
            if (link == null) {
                continue;
            }
            String path = link.attr("href").replaceFirst("^[A-Za-z]+://[^/]+", "");
            if (!JOB_PATH.matcher(path).matches() || path.length() > MAX_EXTERNAL_ID
                    || uri(site(board) + path).isEmpty()) {
                LOG.warn("SuccessFactors {}: posting path skipped: {}", board, path);
                continue;
            }
            rows.add(new FetchedPosting(path, link.text(), site(board) + path,
                    place == null || place.text().isBlank() ? null : place.text().strip(), null));
        }
        return rows;
    }

    /**
     * Объявленное число вакансий — последнее число в {@code .paginationLabel} («Results 1 – 25 of 36»); нет или не
     * число ({@code int} не вмещает) — {@code -1} (список читается, пока страницы не пустые; адаптер не бросает).
     */
    private static int declaredTotal(Document page) {
        Element label = page.selectFirst(".paginationLabel");
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
     * «Regina, SK, CA, S4P 3Y2» SK — провинция, страна CA) — или название страны.
     */
    static boolean inCountry(String location, String country) {
        if (location == null) {
            return false;
        }
        String code = null;
        for (String part : location.split(",")) {
            if (COUNTRY_CODE.matcher(part.strip()).matches()) {
                code = part.strip();
            }
        }
        return code != null ? code.equals(country) : CountryNames.mentions(location, country);
    }

    private static List<FetchedPosting> filter(Map<String, FetchedPosting> postings, String country) {
        return postings.values().stream()
                .filter(posting -> country == null || inCountry(posting.location(), country)).toList();
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
