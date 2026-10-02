package com.roleorienta.worker.adapter.stateportal;

import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Адаптер государственного портала вакансий Služby zamestnanosti (технический документ §5; решение
 * владельца, стенограмма §44–§45). Страницы, открытые соискателю, в HTML.
 *
 * <ul>
 *   <li>Доска — IČO работодателя. Список — {@code /pracovne-ponuky?firma=<IČO>&pageSize=N&pageNr=K}
 *       (K с 1): вакансии самого портала (ссылка {@code /pracovne-ponuky/<uuid>}) и объявления площадок
 *       (profesia.sk, kariera.sk, worki.sk …), которые портал привязал к этому IČO (ссылка на площадку).
 *       Портал объявляет число вакансий («8 pracovných ponúk»); чтение полное, только если прочитано
 *       столько же; меньше — неполное.</li>
 *   <li>Публикация: название, место, ссылка; внешний id — uuid портала, у объявления profesia.sk —
 *       {@code profesia:<номер>}, у прочих площадок — адрес объявления. Текста в списке нет; деталь не
 *       читается — отбор по названию.</li>
 *   <li>Список работодателей — {@code /zamestnavatelia?pageSize=200&pageNr=K}: название и IČO всех
 *       зарегистрированных работодателей (около 28 тыс.); число в нём — только вакансии самого портала,
 *       поэтому наличие вакансий проверяется списком вакансий работодателя
 *       ({@link #hasPostingsIn}).</li>
 *   <li>Портал только словацкий: фильтр страны не нужен.</li>
 * </ul>
 */
@Component
public class StatePortalAdapter implements SourceAdapter {

    /** Код провайдера ({@code source.provider}, {@code source_permission.provider}). */
    public static final String PROVIDER = "sluzbyzamestnanosti";

    /** Работодателей на страницу списка работодателей (наибольшее, что портал отдаёт). */
    static final int EMPLOYERS_PAGE_SIZE = 200;

    private static final Pattern REGISTRATION_NUMBER = Pattern.compile("\\d{8}");
    private static final Pattern DECLARED = Pattern.compile("(\\d[\\d\\s\\u00a0]*)\\s+pracovn(?:ých|é|á)\\s+pon");
    private static final Pattern PORTAL_OFFER = Pattern.compile("/pracovne-ponuky/([0-9a-f]{8}-[0-9a-f-]{27})");
    private static final Pattern PROFESIA_OFFER = Pattern.compile("profesia\\.sk/(?:.*/)?O(\\d+)");
    private static final Pattern EMPLOYER_NUMBER = Pattern.compile("IČO:\\s*(\\d{8})");
    private static final int MAX_EXTERNAL_ID = 200;
    /** Домен почты контакта в детали вакансии: адрес собирается скриптом из имени и хоста. */
    private static final Pattern MAIL_HOST = Pattern.compile("let\\s+host\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern HOST = Pattern.compile("[a-z0-9-]+(?:\\.[a-z0-9-]+)+");
    /** Правовые формы в названии (после разбиения на слова). */
    private static final Set<String> LEGAL_FORMS = Set.of("spol", "sro", "as", "ks", "vos", "se", "ltd", "gmbh",
            "inc", "plc", "druzstvo");
    /** Общие слова названия: их наличие в домене не говорит о компании. */
    private static final Set<String> GENERIC_WORDS = Set.of("slovakia", "slovensko", "slovenska", "slovenskej",
            "group", "company", "services", "service", "holding", "and", "the");
    /** Общие почтовые сервисы: домен почты на них — не сайт работодателя. */
    private static final Set<String> PUBLIC_MAIL = Set.of("gmail.com", "googlemail.com", "azet.sk", "centrum.sk",
            "zoznam.sk", "post.sk", "pobox.sk", "atlas.sk", "inmail.sk", "szm.sk", "stonline.sk", "orangemail.sk",
            "chello.sk", "upcmail.sk", "seznam.cz", "email.cz", "centrum.cz", "yahoo.com", "outlook.com",
            "hotmail.com", "live.com", "icloud.com", "gmx.net", "gmx.de", "gmx.com", "mail.com");
    private static final Logger LOG = LoggerFactory.getLogger(StatePortalAdapter.class);

    private final ExternalHttpClient httpClient;
    private final StatePortalProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес портала и размеры страниц
     */
    public StatePortalAdapter(ExternalHttpClient httpClient, StatePortalProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    /**
     * Все вакансии работодателя на портале, страницами до объявленного числа.
     *
     * @param board IČO работодателя
     * @return публикации; не IČO — постоянный отказ; отказ первой страницы — источник недоступен
     */
    @Override
    public SourceReadResult read(String board) {
        if (!REGISTRATION_NUMBER.matcher(board).matches()) {
            return new SourceReadResult.Unavailable(new HttpResult.PermanentFailure(
                    HttpResult.Kind.BLOCKED, "Not an IČO: " + board));
        }
        Map<String, FetchedPosting> postings = new LinkedHashMap<>();
        List<String> responses = new ArrayList<>();
        int declared = -1;
        for (int page = 1; page <= properties.maxOfferPages(); page++) {
            HttpResult result = httpClient.get(offersUri(board, properties.offersPageSize(), page));
            if (!(result instanceof HttpResult.Success success)) {
                if (page == 1) {
                    return new SourceReadResult.Unavailable(result);
                }
                LOG.warn("State portal employer {} read partially at page {}: {}", board, page, result);
                return SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.PAGE_FAILED,
                        responses);
            }
            responses.add(success.body());
            Document document = Jsoup.parse(success.body(), properties.baseUrl());
            if (page == 1) {
                declared = declaredCount(document).orElse(-1);
            }
            int before = postings.size();
            offers(document).forEach(posting -> postings.putIfAbsent(posting.externalId(), posting));
            if (declared >= 0 && postings.size() >= declared) {
                return SourceReadResult.Read.full(List.copyOf(postings.values()), responses);
            }
            if (postings.size() == before) {
                LOG.warn("State portal employer {}: {} of {} declared postings", board, postings.size(), declared);
                return SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.LIST_ENDED_EARLY,
                        responses);
            }
        }
        LOG.warn("State portal employer {} exceeds {} pages, read partially", board, properties.maxOfferPages());
        return SourceReadResult.Read.partial(List.copyOf(postings.values()), PartialReason.PAGE_LIMIT, responses);
    }

    /**
     * Есть ли у работодателя вакансии на портале — по числу, объявленному на первой странице его списка.
     *
     * @param board   IČO работодателя
     * @param country не используется: портал только словацкий
     * @return есть или нет; пусто — запрос не удался или число не найдено
     */
    @Override
    public Optional<Boolean> hasPostingsIn(String board, String country) {
        if (!REGISTRATION_NUMBER.matcher(board).matches()
                || !(httpClient.get(offersUri(board, 1, 1)) instanceof HttpResult.Success success)) {
            return Optional.empty();
        }
        OptionalInt declared = declaredCount(Jsoup.parse(success.body()));
        return declared.isPresent() ? Optional.of(declared.getAsInt() > 0) : Optional.empty();
    }

    /**
     * Страница списка работодателей портала.
     *
     * @param page номер страницы с 1
     * @return работодатели страницы (пустой список — страницы кончились); пусто — запрос не удался
     */
    public Optional<List<PortalEmployer>> employers(int page) {
        URI uri = URI.create(properties.baseUrl() + "/zamestnavatelia?pageSize=" + EMPLOYERS_PAGE_SIZE
                + "&pageNr=" + page);
        if (!(httpClient.get(uri) instanceof HttpResult.Success success)) {
            return Optional.empty();
        }
        List<PortalEmployer> employers = new ArrayList<>();
        for (Element title : Jsoup.parse(success.body()).select("h3.govuk-signpost__title")) {
            Matcher number = EMPLOYER_NUMBER.matcher(title.parent().text());
            if (number.find()) {
                employers.add(new PortalEmployer(number.group(1), title.text()));
            }
        }
        return Optional.of(employers);
    }

    /**
     * Сайт работодателя: первая своя вакансия портала на первой странице его списка, в её детали —
     * «Internetová adresa», иначе домен контактной почты — не общий почтовый сервис и со словом или
     * инициалами названия компании (почта бывает на домене бухгалтера или агентства; технический
     * документ §5.1). IČO детали должно совпасть с {@code board}.
     *
     * @param board IČO работодателя
     * @return сайт или его отсутствие; пусто — страница не получена
     */
    public Optional<PortalSite> employerSite(String board) {
        PortalSite none = new PortalSite(null, null);
        if (!REGISTRATION_NUMBER.matcher(board).matches()
                || !(httpClient.get(offersUri(board, properties.offersPageSize(), 1))
                        instanceof HttpResult.Success list)) {
            return Optional.empty();
        }
        Matcher offer = PORTAL_OFFER.matcher(list.body());
        if (!offer.find()) {
            return Optional.of(none);
        }
        URI detailUri = URI.create(properties.baseUrl() + "/pracovne-ponuky/" + offer.group(1));
        if (!(httpClient.get(detailUri) instanceof HttpResult.Success detail)) {
            return Optional.empty();
        }
        Document document = Jsoup.parse(detail.body(), detailUri.toString());
        Element number = value(document, "IČO");
        if (number == null || !withoutLeadingZeros(number.text()).equals(withoutLeadingZeros(board))) {
            return Optional.of(none);
        }
        Element web = value(document, "Internetová adresa");
        String host = web == null ? null : host(web.selectFirst("a[href]") == null ? web.text()
                : web.selectFirst("a[href]").attr("href"));
        if (host == null) {
            Matcher mail = MAIL_HOST.matcher(detail.body());
            host = mail.find() ? host(mail.group(1)) : null;
            Element name = value(document, "Názov spoločnosti");
            host = host == null || PUBLIC_MAIL.contains(host) || name == null || !namesCompany(host, name.text())
                    ? null : host;
        }
        return Optional.of(host == null ? none : new PortalSite(host, detailUri.toString()));
    }

    /**
     * Значение поля детали вакансии: {@code dt.vpm-data-panel__key} с подписью → следующий {@code dd}.
     */
    private static Element value(Document document, String key) {
        for (Element term : document.select("dt.vpm-data-panel__key")) {
            if (key.equals(term.text().trim())) {
                return term.nextElementSibling();
            }
        }
        return null;
    }

    /**
     * Хост из адреса или домена ({@code www.firma.sk}, {@code https://firma.sk/sk}); не похоже на хост —
     * {@code null}.
     */
    private static String host(String address) {
        String text = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        try {
            String hostPart = URI.create(text.contains("://") ? text : "https://" + text).getHost();
            return hostPart != null && HOST.matcher(hostPart).matches() ? hostPart : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * В домене есть слово названия (от трёх букв, кроме общих: slovakia, group …) или инициалы его слов (от трёх)
     * ({@code zsr.sk} — «Železnice Slovenskej republiky»); правовая форма не учитывается.
     */
    private static boolean namesCompany(String host, String companyName) {
        String plain = Normalizer.normalize(companyName, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        for (String word : plain.split("[^a-z0-9]+")) {
            if (word.length() > 1 && !LEGAL_FORMS.contains(word)) {
                words.add(word);
            }
        }
        StringBuilder initials = new StringBuilder();
        words.forEach(word -> initials.append(word.charAt(0)));
        String domain = host.startsWith("www.") ? host.substring(4) : host;
        return initials.length() > 2 && domain.contains(initials)
                || words.stream().anyMatch(word -> word.length() > 2 && !GENERIC_WORDS.contains(word)
                        && domain.contains(word));
    }

    private static String withoutLeadingZeros(String number) {
        return number.trim().replaceFirst("^0+", "");
    }

    private URI offersUri(String board, int pageSize, int page) {
        return URI.create(properties.baseUrl() + "/pracovne-ponuky?firma=" + board + "&pageSize=" + pageSize
                + "&pageNr=" + page);
    }

    /**
     * Число вакансий, объявленное порталом («8 pracovných ponúk», «1 pracovná ponuka»).
     */
    private static OptionalInt declaredCount(Document document) {
        for (Element span : document.select("span")) {
            Matcher matcher = DECLARED.matcher(span.text());
            if (span.childrenSize() == 0 && matcher.find()) {
                return OptionalInt.of(Integer.parseInt(matcher.group(1).replaceAll("\\D", "")));
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Вакансии страницы: карточка — заголовок {@code h3.govuk-signpost__title}, под ним работодатель
     * (жирный абзац) и место (следующий абзац); ссылка — сама карточка (вакансия портала, адрес без
     * параметров поиска) или ссылка «Zobraziť na portáli …» (объявление площадки).
     */
    private static List<FetchedPosting> offers(Document document) {
        List<FetchedPosting> postings = new ArrayList<>();
        for (Element title : document.select("h3.govuk-signpost__title")) {
            Element card = title.parent();
            Element link = title.closest("a[href]");
            if (link == null) {
                link = card.selectFirst("a[href]");
            }
            if (link == null) {
                continue;
            }
            String url = link.absUrl("href");
            String externalId = externalId(url);
            if (PORTAL_OFFER.matcher(url).find()) {
                url = url.replaceFirst("[?#].*$", "");
            }
            if (externalId != null) {
                Element place = card.selectFirst("p.govuk-signpost__description:not(.vpm-font-bold)");
                postings.add(new FetchedPosting(externalId, title.text(), url,
                        place == null || place.text().isBlank() ? null : place.text(), null));
            }
        }
        return postings;
    }

    /**
     * uuid вакансии портала; {@code profesia:<номер>}; адрес объявления другой площадки; {@code null} —
     * адрес не годится (длиннее поля внешнего id).
     */
    private static String externalId(String url) {
        Matcher portal = PORTAL_OFFER.matcher(url);
        if (portal.find()) {
            return portal.group(1);
        }
        Matcher profesia = PROFESIA_OFFER.matcher(url);
        if (profesia.find()) {
            return "profesia:" + profesia.group(1);
        }
        return url.isBlank() || url.length() > MAX_EXTERNAL_ID ? null : url;
    }
}
