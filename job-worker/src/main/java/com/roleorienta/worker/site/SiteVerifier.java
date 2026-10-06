package com.roleorienta.worker.site;

import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Проверка, что сайт принадлежит компании (алгоритм поиска сайта версии 3; технический документ §5.1):
 * открывается стартовая страница; IČO компании рядом с подписью в её видимом тексте ({@link IcoExtractor}) или
 * на одной из первых {@link #LEGAL_LINKS_MAX} ссылок с неё на контакты и реквизиты того же хоста (kontakt,
 * impressum, ochrana osobných údajov, obchodné podmienky, o nás …); иначе — бренд названия ({@link SiteBrand}).
 * Общий компонент шагов, которые проверяют найденный адрес: почта портала (шаг 2), адрес по названию (шаги 3–4).
 */
@Component
public class SiteVerifier {

    /** Ссылок на реквизиты, открываемых сверх стартовой страницы. */
    static final int LEGAL_LINKS_MAX = 2;

    private static final Pattern LEGAL_LINK = Pattern.compile("(?iu)kontakt|contact|impressum|imprint|"
            + "ochrana osobn|osobn[ýy]ch [úu]dajov|gdpr|privacy|obchodn[ée] podmienky|pr[áa]vne inform|o n[áa]s|"
            + "o-nas|o spolo[čc]nosti|about");

    private static final String HOST_PLACEHOLDER = "{host}";

    private final ExternalHttpClient httpClient;
    private final SiteCheckProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент (бюджет на хост, robots.txt, потолок тела)
     * @param properties адрес главной по хосту
     */
    public SiteVerifier(ExternalHttpClient httpClient, SiteCheckProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * Проверяет сайт со стартовой страницы: главная ({@link SiteCheckProperties#homeUrl()}) и путь от неё.
     *
     * @param host               хост сайта; по нему же — правила бренда (домен и зона)
     * @param path               путь стартовой страницы без ведущей косой черты ({@code sk/}); пустой — главная
     * @param registrationNumber IČO компании (ведущие нули не важны)
     * @param companyName        название компании из реестра
     * @return итог проверки; временный отказ стартовой страницы — {@link Verdict#TEMPORARY}; отказы ссылок на
     *         реквизиты итог не меняют
     */
    public Verdict verify(String host, String path, String registrationNumber, String companyName) {
        URI start = start(host, path);
        return switch (httpClient.get(start)) {
            case HttpResult.TemporaryFailure temporary -> Verdict.TEMPORARY;
            case HttpResult.PermanentFailure failure -> switch (failure.kind()) {
                case ACCESS_DENIED, USE_FORBIDDEN -> Verdict.CLOSED;
                case TOO_LARGE -> Verdict.OPENED;
                default -> Verdict.GONE;
            };
            case HttpResult.Success success -> verdict(start, host, success.body(), registrationNumber, companyName);
        };
    }

    /**
     * @param host хост сайта
     * @param path путь без ведущей косой черты; пустой — главная
     * @return адрес стартовой страницы: главная по {@link SiteCheckProperties#homeUrl()} и путь от неё
     */
    public URI start(String host, String path) {
        return URI.create(properties.homeUrl().replace(HOST_PLACEHOLDER, host)).resolve(path);
    }

    /**
     * Итог по открывшейся стартовой странице: IČO на ней или на странице реквизитов, иначе бренд, иначе признак
     * сайта группы.
     */
    private Verdict verdict(URI start, String host, String html, String registrationNumber, String companyName) {
        Document page = Jsoup.parse(html, start.toString());
        String number = withoutLeadingZeros(registrationNumber);
        if (hasNumber(page, number) || legalLinks(page, start).stream().anyMatch(link -> numberAt(link, number))) {
            return Verdict.REGISTRATION_NUMBER;
        }
        if (SiteBrand.confirmed(host, page, html, companyName)) {
            return Verdict.BRAND;
        }
        return SiteBrand.groupSite(host, page, html, companyName) ? Verdict.GROUP : Verdict.OPENED;
    }

    private boolean numberAt(URI link, String number) {
        return httpClient.get(link) instanceof HttpResult.Success success
                && hasNumber(Jsoup.parse(success.body(), link.toString()), number);
    }

    private static boolean hasNumber(Document page, String number) {
        return IcoExtractor.extract(page.text()).stream().anyMatch(found -> withoutLeadingZeros(found).equals(number));
    }

    /**
     * Ссылки на реквизиты: тот же хост, не сама страница, подпись или адрес — слово реквизитов; без повторов.
     */
    private static Set<URI> legalLinks(Document page, URI start) {
        Set<URI> links = new LinkedHashSet<>();
        for (Element anchor : page.select("a[href]")) {
            String href = anchor.absUrl("href").replaceFirst("#.*$", "");
            if (links.size() == LEGAL_LINKS_MAX) {
                break;
            }
            if (!LEGAL_LINK.matcher(anchor.text()).find() && !LEGAL_LINK.matcher(anchor.attr("href")).find()) {
                continue;
            }
            try {
                URI link = URI.create(href);
                if (start.getHost() != null && start.getHost().equalsIgnoreCase(link.getHost())
                        && !trimmed(link).equals(trimmed(start))) {
                    links.add(link);
                }
            } catch (IllegalArgumentException malformed) {
                // ссылка с недопустимыми символами — пропускается
            }
        }
        return links;
    }

    private static String trimmed(URI uri) {
        return uri.toString().replaceFirst("/+$", "").toLowerCase(Locale.ROOT);
    }

    private static String withoutLeadingZeros(String number) {
        return number.trim().replaceFirst("^0+", "");
    }

    /**
     * Итог проверки сайта.
     */
    public enum Verdict {
        /** IČO компании на стартовой странице или на странице реквизитов. */
        REGISTRATION_NUMBER,
        /** IČO нет, бренд названия подтверждает сайт. */
        BRAND,
        /** Страница открылась, подтверждения нет; похожа на международный сайт группы ({@link SiteBrand#groupSite}). */
        GROUP,
        /** Страница открылась, подтверждения нет. */
        OPENED,
        /** Сайт закрыт для программы: 401/403 или robots.txt. */
        CLOSED,
        /** Сайт не существует (в том числе домена нет), удалён или адрес недопустим. */
        GONE,
        /** Временный отказ: проверить позже. */
        TEMPORARY
    }
}
