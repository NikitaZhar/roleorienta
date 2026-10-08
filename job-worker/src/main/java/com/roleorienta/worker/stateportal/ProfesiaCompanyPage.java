package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.career.CareerLinks;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Разбор страниц profesia.sk для шага «страница компании» (§82): объявление → страница компании
 * ({@code /praca/<компания>/C<номер>}) и её разделы ({@code ?page_num=}) → внешние ссылки компании. Ссылки самой
 * profesia.sk (подвал сети сайтов, шапка) и соцсетей не берутся.
 */
final class ProfesiaCompanyPage {

    /** Страница компании: путь {@code /praca/<компания>/C<номер>}. */
    private static final Pattern COMPANY_PATH = Pattern.compile("/praca/[^/]+/(C\\d+)");
    /** Разделов страницы компании берётся не больше (страница «Open positions» у Henkel — раздел). */
    private static final int MAX_SECTIONS = 3;
    /** Подвал и шапка profesia.sk — ссылки сети сайтов profesia, не компании. */
    private static final String PROFESIA_CHROME = "#profesia-footer a[href], header a[href]";
    private static final Pattern NOT_COMPANY_HOSTS = Pattern.compile(
            "profesia|facebook|instagram|linkedin|youtube|youtu\\.be|twitter|(^|\\.)x\\.com$|tiktok|whatsapp|viber"
                    + "|google|apple\\.com|spotify|vimeo|pinterest");

    private ProfesiaCompanyPage() {
    }

    /**
     * @param ad страница объявления на profesia.sk (с адресом — {@code baseUri})
     * @return адрес страницы компании; пусто — ссылки нет
     */
    static Optional<String> companyPage(Document ad) {
        for (Element anchor : ad.select("a[href]")) {
            String url = anchor.absUrl("href");
            URI uri = uri(url);
            if (uri != null && uri.getPath() != null && COMPANY_PATH.matcher(uri.getPath()).matches()
                    && uri.getQuery() == null) {
                return Optional.of(url);
            }
        }
        return Optional.empty();
    }

    /**
     * @param company страница компании на profesia.sk
     * @return адреса её разделов ({@code /praca/<…>/C<номер>?page_num=…}, тот же номер компании), не больше
     *         {@value #MAX_SECTIONS}
     */
    static List<String> sections(Document company) {
        URI page = uri(company.location());
        Matcher own = page == null || page.getPath() == null ? null : COMPANY_PATH.matcher(page.getPath());
        List<String> sections = new ArrayList<>();
        if (own == null || !own.matches()) {
            return sections;
        }
        for (Element anchor : company.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            if (link == null || link.getPath() == null || link.getQuery() == null
                    || !link.getQuery().startsWith("page_num=")) {
                continue;
            }
            Matcher matcher = COMPANY_PATH.matcher(link.getPath());
            if (matcher.matches() && matcher.group(1).equals(own.group(1)) && !sections.contains(link.toString())) {
                sections.add(link.toString());
                if (sections.size() == MAX_SECTIONS) {
                    break;
                }
            }
        }
        return sections;
    }

    /**
     * @param page страница компании или её раздел
     * @return внешние ссылки компании по порядку на странице: другой хост, не profesia.sk и не соцсети, вне подвала
     *         и шапки
     */
    static List<String> companyLinks(Document page) {
        List<Element> chrome = page.select(PROFESIA_CHROME);
        URI self = uri(page.location());
        String ownHost = self == null ? "" : self.getHost();
        List<String> links = new ArrayList<>();
        for (Element anchor : page.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            if (link == null || chrome.contains(anchor) || !link.getScheme().startsWith("http")
                    || link.getHost().equalsIgnoreCase(ownHost)
                    || NOT_COMPANY_HOSTS.matcher(link.getHost().toLowerCase(Locale.ROOT)).find()
                    || links.contains(link.toString())) {
                continue;
            }
            links.add(link.toString());
        }
        return links;
    }

    /**
     * Ссылка для записи: первая кадровая ({@link CareerLinks#careerLink}), иначе первая (сайт компании).
     *
     * @param links внешние ссылки компании
     * @return выбранная ссылка; пусто — ссылок нет
     */
    static Optional<String> choose(List<String> links) {
        return links.stream().filter(CareerLinks::careerLink).findFirst().or(() -> links.stream().findFirst());
    }

    private static URI uri(String link) {
        try {
            URI uri = new URI(link);
            return uri.getHost() == null || uri.getScheme() == null ? null : uri;
        } catch (URISyntaxException malformed) {
            return null;
        }
    }
}
