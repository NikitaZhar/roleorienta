package com.roleorienta.worker.adapter.smartrecruiters;

import com.roleorienta.worker.adapter.CountryNames;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Адаптер SmartRecruiters (решение владельца, §35): страницы, открытые соискателю, — не API
 * ({@code api.smartrecruiters.com} запрещён robots.txt).
 *
 * <ul>
 *   <li>Доска — идентификатор компании в адресе ({@code careers.smartrecruiters.com/<компания>}),
 *       в нижнем регистре.</li>
 *   <li>Список — {@code <careers>/<компания>?search=&page=N}, N с 0: вакансии сгруппированы по
 *       месту (заголовок группы — «Košice, Slovakia (Slovak Republic)»), ссылка на вакансию —
 *       {@code jobs.smartrecruiters.com/<компания>/<id>-<название>}. Число страниц объявлено на
 *       первой ({@code data-groups-pages}; у JYSK — 167) — читаются все; не объявлено — пока
 *       страницы приносят новые вакансии. Отказ на первой — источник недоступен, на следующих или
 *       упор в потолок — неполное чтение.</li>
 *   <li>С фильтром страны в список входят только вакансии с местом в этой стране — по заголовку
 *       группы: у доски может быть весь мир, а текст нужен только вакансий страны сбора.</li>
 *   <li>Текст — страница вакансии {@code <jobs>/<компания>/<id>}, микроразметка schema.org
 *       {@code JobPosting}: {@code description}, место — {@code addressLocality} и
 *       {@code addressCountry}. На странице стоит {@code noindex,nofollow} — просьба к поисковикам;
 *       текст читается один раз на вакансию, только для отбора, пользователю не показывается
 *       (решение владельца, технический документ §10).</li>
 * </ul>
 */
@Component
public class SmartRecruitersAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "smartrecruiters";

    private static final Pattern BOARD = Pattern.compile("[a-z0-9_-]+");
    private static final String PAGES_ATTRIBUTE = "data-groups-pages";
    private static final Pattern JOB_ID = Pattern.compile("/(\\d{6,})(?:-[^/?#]*)?(?:[?#].*)?$");
    private static final Logger LOG = LoggerFactory.getLogger(SmartRecruitersAdapter.class);

    private final ExternalHttpClient httpClient;
    private final SmartRecruitersProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адреса и потолок страниц
     */
    public SmartRecruitersAdapter(ExternalHttpClient httpClient, SmartRecruitersProperties properties) {
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
                    HttpResult.Kind.BLOCKED, "Not a SmartRecruiters company: " + board));
        }
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        List<String> responses = new ArrayList<>();
        int pageCount = 0;
        for (int page = 0; page < properties.maxPages(); page++) {
            HttpResult result = httpClient.get(URI.create(properties.careersUrl() + "/" + board + "?search=&page=" + page));
            if (!(result instanceof HttpResult.Success success)) {
                if (page == 0) {
                    return new SourceReadResult.Unavailable(result);
                }
                LOG.warn("SmartRecruiters {} read partially at page {}: {}", board, page, result);
                return SourceReadResult.Read.partial(filter(postings, country), PartialReason.PAGE_FAILED, responses);
            }
            responses.add(success.body());
            Document document = Jsoup.parse(success.body());
            if (page == 0) {
                pageCount = declaredPages(document);
            }
            int before = postings.size();
            page(document, board).forEach(posting -> postings.putIfAbsent(posting.externalId(), posting));
            boolean lastPage = pageCount > 0 ? page + 1 >= pageCount : postings.size() == before;
            if (lastPage) {
                return SourceReadResult.Read.full(filter(postings, country), responses);
            }
        }
        LOG.warn("SmartRecruiters {} exceeds {} pages, read partially", board, properties.maxPages());
        return SourceReadResult.Read.partial(filter(postings, country), PartialReason.PAGE_LIMIT, responses);
    }

    /**
     * Текст и место со страницы вакансии (микроразметка {@code JobPosting}).
     */
    @Override
    public FetchedPosting detail(String board, String externalId) {
        if (!BOARD.matcher(board).matches() || !externalId.chars().allMatch(Character::isDigit)) {
            return null;
        }
        String url = postingUrl(board, externalId);
        if (!(httpClient.get(URI.create(url)) instanceof HttpResult.Success success)) {
            return null;
        }
        Element posting = Jsoup.parse(success.body()).selectFirst("[itemtype*=JobPosting]");
        if (posting == null) {
            LOG.warn("SmartRecruiters posting {} on {}: no JobPosting markup", externalId, board);
            return null;
        }
        Element title = posting.selectFirst("[itemprop=title]");
        Element description = posting.selectFirst("[itemprop=description]");
        return new FetchedPosting(externalId, title == null ? null : title.text(), url, location(posting),
                description == null ? null : description.html());
    }

    /**
     * Вакансии страницы списка: место — заголовок группы.
     */
    private List<FetchedPosting> page(Document page, String board) {
        List<FetchedPosting> postings = new ArrayList<>();
        for (Element group : page.select("section.openings-section")) {
            Element header = group.selectFirst(".opening-title");
            String location = header == null ? null : header.text();
            for (Element link : group.select("li.opening-job a[href]")) {
                Matcher id = JOB_ID.matcher(link.attr("href"));
                Element title = link.selectFirst(".job-title");
                if (id.find() && title != null) {
                    postings.add(new FetchedPosting(id.group(1), title.text(), postingUrl(board, id.group(1)),
                            location, null));
                }
            }
        }
        return postings;
    }

    /**
     * Число страниц списка, объявленное на первой ({@code data-groups-pages}); нет или не число — 0.
     */
    private static int declaredPages(Document page) {
        Element openings = page.selectFirst("[" + PAGES_ATTRIBUTE + "]");
        String value = openings == null ? "" : openings.attr(PAGES_ATTRIBUTE);
        return value.matches("\\d{1,6}") ? Integer.parseInt(value) : 0;
    }

    private static List<FetchedPosting> filter(Map<String, FetchedPosting> postings, String country) {
        return postings.values().stream().filter(posting -> country == null
                || posting.location() != null && CountryNames.mentions(posting.location(), country)).toList();
    }

    /**
     * «Košice, Slovakia (Slovak Republic)» из {@code addressLocality} и {@code addressCountry};
     * нет ни того, ни другого — {@code null}.
     */
    private static String location(Element posting) {
        String locality = meta(posting, "addressLocality");
        String country = meta(posting, "addressCountry");
        if (locality == null) {
            return country;
        }
        return country == null ? locality : locality + ", " + country;
    }

    private static String meta(Element posting, String property) {
        Element meta = posting.selectFirst("[itemprop=" + property + "]");
        String value = meta == null ? "" : meta.hasAttr("content") ? meta.attr("content") : meta.text();
        return value.isBlank() ? null : value.strip();
    }

    private String postingUrl(String board, String id) {
        return properties.jobsUrl() + "/" + board + "/" + id;
    }
}
