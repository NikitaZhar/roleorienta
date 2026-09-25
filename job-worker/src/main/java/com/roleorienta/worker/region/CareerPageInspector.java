package com.roleorienta.worker.region;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.adapters.personio.PersonioAdapter;
import com.roleorienta.worker.adapters.personio.PersonioBoard;
import com.roleorienta.worker.collect.NicheFilterProperties;
import com.roleorienta.worker.http.SourceHttpClient;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Карьерная страница на сайте компании и её тип (план R4, R5, R7; §95). На главной ищется ссылка на
 * раздел вакансий (kariéra, career, jobs, práca…). Тип страницы:
 * <ul>
 *   <li>ссылка или страница ведут на систему найма ({@link #SYSTEMS}: Workday, Personio, Greenhouse,
 *       Lever, Teamtailor, Recruitee, SmartRecruiters, Recruitis…) — код системы; для Personio
 *       вакансии считаются готовым адаптером;</li>
 *   <li>на странице есть разметка schema.org {@code JobPosting} (JSON-LD) — {@code schema-org},
 *       вакансии считаются по разметке;</li>
 *   <li>иначе — {@code own-page} (своя страница без разметки) или {@code none} (раздела нет).</li>
 * </ul>
 * Это замер (R7): вакансии не сохраняются, считаются только их число и число в нише пилота.
 */
@Component
public class CareerPageInspector {

    /** Признаки системы найма в адресе: подстрока хоста → код системы (подстроки не пересекаются). */
    static final Map<String, String> SYSTEMS = Map.ofEntries(
            Map.entry("myworkdayjobs.com", "workday"), Map.entry("jobs.personio.", "personio"),
            Map.entry("greenhouse.io", "greenhouse"), Map.entry("lever.co", "lever"),
            Map.entry("teamtailor.com", "teamtailor"), Map.entry("recruitee.com", "recruitee"),
            Map.entry("smartrecruiters.com", "smartrecruiters"), Map.entry("recruitis.io", "recruitis"),
            Map.entry("traffit.com", "traffit"), Map.entry("softgarden.io", "softgarden"),
            Map.entry("join.com", "join"), Map.entry("workable.com", "workable"),
            Map.entry("bamboohr.com", "bamboohr"), Map.entry("breezy.hr", "breezy"),
            Map.entry("successfactors.", "successfactors"), Map.entry("teamio.com", "teamio"));

    /** Текст или адрес ссылки на раздел вакансий. */
    private static final Pattern CAREER_LINK = Pattern.compile(
            "kari[eé]r|career|karriere|jobs?\\b|pr[aá]c[ae]\\b|voln[eé] (pracovn[eé] )?(miesta|poz[ií]cie)"
                    + "|join (us|our team)|pridaj sa|hir(e|ing)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final SourceHttpClient httpClient;
    private final SourceAdapterRegistry adapters;
    private final NicheFilterProperties niche;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param httpClient единый HTTP-клиент воркера
     * @param adapters   адаптеры систем найма (подсчёт вакансий на доске)
     * @param niche      ниша пилота (заголовки)
     */
    public CareerPageInspector(SourceHttpClient httpClient, SourceAdapterRegistry adapters,
                               NicheFilterProperties niche) {
        this.httpClient = httpClient;
        this.adapters = adapters;
        this.niche = niche;
    }

    /**
     * Карьерная страница сайта.
     *
     * @param site подтверждённый сайт
     * @return страница и её тип; {@code none}, если ссылки на раздел вакансий нет
     */
    public CareerPage inspect(SiteLocator.Site site) {
        Optional<String> link = careerLink(site.html(), site.url());
        if (link.isEmpty()) {
            return new CareerPage(null, CareerPage.NONE, null, null);
        }
        String url = link.get();
        Optional<String> system = systemOf(url);
        if (system.isPresent()) {
            return onSystem(url, system.get());
        }
        String html;
        try {
            html = httpClient.getBody(url);
        } catch (RuntimeException unreachable) {
            return new CareerPage(url, CareerPage.OWN_PAGE, null, null);
        }
        Optional<String> embedded = Jsoup.parse(html, url).select("a[href], iframe[src], script[src]").stream()
                .map(element -> element.hasAttr("href") ? element.absUrl("href") : element.absUrl("src"))
                .filter(address -> systemOf(address).isPresent()).findFirst();
        if (embedded.isPresent()) {
            return onSystem(embedded.get(), systemOf(embedded.get()).orElseThrow());
        }
        List<String> titles = jobPostingTitles(html);
        if (!titles.isEmpty()) {
            return new CareerPage(url, CareerPage.SCHEMA_ORG, titles.size(), nicheCount(titles));
        }
        return new CareerPage(url, CareerPage.OWN_PAGE, null, null);
    }

    /**
     * Первая ссылка главной страницы на раздел вакансий (по тексту или адресу), абсолютным адресом.
     *
     * @param html    главная страница
     * @param baseUrl её адрес
     * @return адрес раздела
     */
    static Optional<String> careerLink(String html, String baseUrl) {
        Document document = Jsoup.parse(html, baseUrl);
        for (Element anchor : document.select("a[href]")) {
            String href = anchor.absUrl("href");
            if (href.startsWith("http") && (CAREER_LINK.matcher(anchor.text()).find()
                    || CAREER_LINK.matcher(pathOf(href)).find() || systemOf(href).isPresent())) {
                return Optional.of(href);
            }
        }
        return Optional.empty();
    }

    /**
     * Система найма по адресу.
     *
     * @param url адрес
     * @return код системы; пусто — адрес не системы найма
     */
    static Optional<String> systemOf(String url) {
        String host = hostOf(url);
        return SYSTEMS.entrySet().stream().filter(entry -> host.contains(entry.getKey()))
                .map(Map.Entry::getValue).findFirst();
    }

    /**
     * Заголовки вакансий из разметки schema.org {@code JobPosting} (JSON-LD, в том числе внутри
     * {@code @graph} и {@code ItemList}).
     *
     * @param html страница
     * @return заголовки; пусто — разметки нет
     */
    List<String> jobPostingTitles(String html) {
        List<String> titles = new ArrayList<>();
        for (Element script : Jsoup.parse(html).select("script[type=application/ld+json]")) {
            try {
                collectJobPostings(objectMapper.readTree(script.data()), titles);
            } catch (JsonProcessingException malformed) {
                // Битый JSON-LD на странице — не вакансии; остальные блоки разбираются дальше.
            }
        }
        return titles;
    }

    private static void collectJobPostings(JsonNode node, List<String> titles) {
        if (node.isArray()) {
            node.forEach(item -> collectJobPostings(item, titles));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (isJobPosting(node.path("@type"))) {
            titles.add(node.path("title").asText(""));
            return;
        }
        node.forEach(child -> collectJobPostings(child, titles));
    }

    private static boolean isJobPosting(JsonNode type) {
        if (type.isArray()) {
            for (JsonNode item : type) {
                if ("JobPosting".equals(item.asText())) {
                    return true;
                }
            }
            return false;
        }
        return "JobPosting".equals(type.asText());
    }

    /** Доска системы найма: для Personio вакансии считаются адаптером, для прочих — не считаются. */
    private CareerPage onSystem(String url, String system) {
        if (PersonioAdapter.PROVIDER_CODE.equals(system)) {
            Optional<PersonioBoard> board = PersonioBoard.fromCareerUrl(url);
            if (board.isPresent()) {
                Source source = new Source();
                source.setExternalRef(board.get().slug());
                source.setBaseUrl(board.get().baseUrl());
                try {
                    PostingsPage page = adapters.forProviderCode(system).listPostings(source, null);
                    List<String> titles = page.postings().stream().map(DiscoveredPosting::rawTitle).toList();
                    return new CareerPage(board.get().baseUrl(), system, titles.size(), nicheCount(titles));
                } catch (RuntimeException unreadable) {
                    return new CareerPage(board.get().baseUrl(), system, null, null);
                }
            }
        }
        return new CareerPage(url, system, null, null);
    }

    private int nicheCount(List<String> titles) {
        return (int) titles.stream().filter(niche::matches).count();
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    private static String pathOf(String url) {
        try {
            String path = URI.create(url.strip()).getPath();
            return path == null ? "" : path;
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }
}
