package com.roleorienta.worker.adapter.stateportal;

import com.roleorienta.worker.adapter.PostedDates;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Адаптер государственного портала вакансий Služby zamestnanosti (технический документ §5; решение
 * владельца, стенограмма §44–§45). Страницы, открытые соискателю, в HTML.
 *
 * <ul>
 *   <li>Вакансии портала читаются общим списком ({@link StatePortalSearch}, стенограмма §105, §107) и записываются в
 *       источники портала (доска — IČO работодателя) заданием {@code PORTAL_RECORD}; чтение источника по работодателю
 *       ({@link #read}) не применяется.</li>
 *   <li>Список работодателей — {@code /zamestnavatelia?pageSize=200&pageNr=K}: название и IČO всех
 *       зарегистрированных работодателей (около 28 тыс.) — для поиска сайта и IČO по названию.</li>
 *   <li>Сайт работодателя — из страницы его первой своей вакансии ({@link #employerSite}).</li>
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
    private static final Pattern PORTAL_OFFER = Pattern.compile("/pracovne-ponuky/([0-9a-f]{8}-[0-9a-f-]{27})");
    /** Объявление profesia.sk: адрес начинается с её хоста; «profesia.sk/…» в параметре чужого адреса — нет (§182). */
    private static final Pattern PROFESIA_OFFER =
            Pattern.compile("(?i)^https?://(?:www\\.)?profesia\\.sk/(?:.*/)?O(\\d+)");
    private static final Pattern EMPLOYER_NUMBER = Pattern.compile("IČO:\\s*(\\d{8})");
    /** Внешний id своей вакансии портала — uuid. */
    private static final Pattern OFFER_UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f-]{27}");
    /** Начало внешнего id объявления profesia.sk ({@link #externalId}). */
    private static final String PROFESIA_ID = "profesia:";
    /** Дата в «Dátum a čas vytvorenia» своей вакансии портала: {@code 04.09.2026 o 09:58}. */
    private static final Pattern PORTAL_DAY = Pattern.compile("(\\d{1,2}\\.\\d{1,2}\\.\\d{4})");
    private static final DateTimeFormatter PORTAL_DATE = DateTimeFormatter.ofPattern("d.M.uuuu");
    /**
     * Профессия своей вакансии портала (§203): «Profesia (SK ISCO-08)» — {@code 9333004 - Pomocný pracovník v sklade};
     * код SK ISCO-08 — 7 цифр, первые 4 — группа ISCO-08.
     */
    private static final Pattern PORTAL_OCCUPATION = Pattern.compile("^\\s*(\\d{4})\\d{3}\\b");
    private static final String OCCUPATION_FIELD = "Profesia (SK ISCO-08)";
    private static final int MAX_EXTERNAL_ID = 200;
    /** Длина поля адреса стартовой страницы ({@code company_site.start_url}). */
    private static final int MAX_ADDRESS = 2000;
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
     * Не применяется: вакансии портала записываются в источники из общего списка (задание {@code PORTAL_RECORD},
     * стенограмма §107), чтение по работодателю планировщик не ставит. Вызов — ошибка кода: задание завершается
     * неудачей, публикации источника не трогаются.
     *
     * @param board IČO работодателя
     * @return не возвращает
     * @throws UnsupportedOperationException всегда
     */
    @Override
    public SourceReadResult read(String board) {
        throw new UnsupportedOperationException("State portal sources are recorded from the general list: " + board);
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
     * «Internetová adresa» полным адресом, иначе домен контактной почты — не общий почтовый сервис и со словом
     * или инициалами названия компании (почта бывает на домене бухгалтера или агентства; технический документ
     * §5.1). Домен почты здесь не проверяется — это делает шаг поиска сайта. IČO детали должно совпасть с
     * {@code board}.
     *
     * @param board IČO работодателя
     * @return сайт или {@link PortalSite#NONE}; пусто — страница не получена
     */
    public Optional<PortalSite> employerSite(String board) {
        if (!REGISTRATION_NUMBER.matcher(board).matches()
                || !(httpClient.get(offersUri(board, properties.offersPageSize(), 1))
                        instanceof HttpResult.Success list)) {
            return Optional.empty();
        }
        Matcher offer = PORTAL_OFFER.matcher(list.body());
        if (!offer.find()) {
            return Optional.of(PortalSite.NONE);
        }
        URI detailUri = URI.create(properties.baseUrl() + "/pracovne-ponuky/" + offer.group(1));
        if (!(httpClient.get(detailUri) instanceof HttpResult.Success detail)) {
            return Optional.empty();
        }
        Document document = Jsoup.parse(detail.body(), detailUri.toString());
        Element number = value(document, "IČO");
        if (number == null || !withoutLeadingZeros(number.text()).equals(withoutLeadingZeros(board))) {
            return Optional.of(PortalSite.NONE);
        }
        Element web = value(document, "Internetová adresa");
        String website = web == null ? null : website(web.selectFirst("a[href]") == null ? web.text()
                : web.selectFirst("a[href]").attr("href"));
        if (website != null) {
            return Optional.of(new PortalSite(website, null, detailUri.toString()));
        }
        Matcher mail = MAIL_HOST.matcher(detail.body());
        String mailHost = mail.find() ? host(mail.group(1)) : null;
        Element name = value(document, "Názov spoločnosti");
        if (mailHost == null || PUBLIC_MAIL.contains(mailHost) || name == null || !namesCompany(mailHost, name.text())) {
            return Optional.of(PortalSite.NONE);
        }
        return Optional.of(new PortalSite(null, mailHost, detailUri.toString()));
    }

    /**
     * Сведения со страницы объявления вакансии общего списка (§168, §203).
     *
     * @param postedAt   дата размещения (начало суток UTC); {@code null} — на странице нет
     * @param occupation группа ISCO-08 (4 цифры) профессии своей вакансии портала; {@code null} — нет
     */
    public record OfferPage(Instant postedAt, String occupation) {
    }

    /**
     * У вакансии общего списка дату размещения можно получить со страницы объявления (§168): своя вакансия портала или
     * объявление profesia.sk; у объявлений других площадок — нет.
     *
     * @param externalId внешний id публикации ({@link #externalId})
     * @return есть ли страница с датой
     */
    public static boolean hasPostedDate(String externalId) {
        return hasOccupation(externalId) || externalId.startsWith(PROFESIA_ID);
    }

    /**
     * Код профессии SK ISCO-08 есть только на странице своей вакансии портала (§203); у объявлений profesia.sk — нет.
     *
     * @param externalId внешний id публикации
     * @return есть ли страница с кодом профессии
     */
    public static boolean hasOccupation(String externalId) {
        return OFFER_UUID.matcher(externalId).matches();
    }

    /**
     * Страница объявления вакансии общего списка (§168, §203): в общем списке портала нет ни даты размещения (только
     * последнее изменение — датой размещения не считается), ни профессии. Своя вакансия портала — страница
     * {@code /pracovne-ponuky/<uuid>}: дата — «Dátum a čas vytvorenia», профессия — «Profesia (SK ISCO-08)»; объявление
     * profesia.sk — страница по его адресу: дата — разметка schema.org {@code itemprop="datePosted"} («Dátum
     * zverejnenia»), профессии нет.
     *
     * @param externalId внешний id публикации
     * @param url        адрес объявления (для profesia.sk)
     * @return сведения страницы; пусто — страница не получена или у объявления нет такой страницы
     */
    public Optional<OfferPage> offerPage(String externalId, String url) {
        if (hasOccupation(externalId)) {
            URI uri = URI.create(properties.baseUrl() + "/pracovne-ponuky/" + externalId);
            if (!(httpClient.get(uri) instanceof HttpResult.Success page)) {
                return Optional.empty();
            }
            Document document = Jsoup.parse(page.body(), uri.toString());
            Element created = value(document, "Dátum a čas vytvorenia");
            Matcher day = created == null ? null : PORTAL_DAY.matcher(created.text());
            Element occupation = value(document, OCCUPATION_FIELD);
            Matcher code = occupation == null ? null : PORTAL_OCCUPATION.matcher(occupation.text());
            return Optional.of(new OfferPage(day != null && day.find() ? portalDay(day.group(1)).orElse(null) : null,
                    code != null && code.find() ? code.group(1) : null));
        }
        if (!externalId.startsWith(PROFESIA_ID) || url == null) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        if (!(httpClient.get(uri) instanceof HttpResult.Success page)) {
            return Optional.empty();
        }
        Element posted = Jsoup.parse(page.body(), url).selectFirst("[itemprop=datePosted]");
        return Optional.of(new OfferPage(posted == null ? null
                : PostedDates.parse(posted.hasAttr("content") ? posted.attr("content") : posted.text()), null));
    }

    private static Optional<Instant> portalDay(String text) {
        try {
            return Optional.of(PostedDates.day(LocalDate.parse(text, PORTAL_DATE)));
        } catch (DateTimeParseException invalid) {
            return Optional.empty();
        }
    }

    /**
     * Полный адрес сайта ({@code www.firma.sk/sk/kariera} → {@code https://www.firma.sk/sk/kariera}); без схемы
     * — {@code https}; хост не похож на хост или адрес длиннее поля — {@code null}.
     */
    private static String website(String address) {
        String text = address == null ? "" : address.trim();
        String absolute = text.contains("://") ? text : "https://" + text;
        if (host(text) == null || absolute.length() > MAX_ADDRESS) {
            return null;
        }
        try {
            URI uri = URI.create(absolute);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())
                    ? uri.toString() : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * Значение поля детали вакансии: {@code dt.vpm-data-panel__key} с подписью → следующий {@code dd}.
     */
    static Element value(Document document, String key) {
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
     * uuid вакансии портала; {@code profesia:<номер>}; адрес объявления другой площадки; {@code null} —
     * адрес не годится (длиннее поля внешнего id).
     */
    static String externalId(String url) {
        Matcher portal = PORTAL_OFFER.matcher(url);
        if (portal.find()) {
            return portal.group(1);
        }
        Matcher profesia = PROFESIA_OFFER.matcher(url);
        if (profesia.find()) {
            return PROFESIA_ID + profesia.group(1);
        }
        return url.isBlank() || url.length() > MAX_EXTERNAL_ID ? null : url;
    }
}
