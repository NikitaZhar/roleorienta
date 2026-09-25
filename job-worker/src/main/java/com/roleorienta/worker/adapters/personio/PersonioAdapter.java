package com.roleorienta.worker.adapters.personio;

import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.BoardProfile;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.FetchedPosting;
import com.roleorienta.worker.adapters.MarketScope;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.http.SourceHttpClient;
import com.roleorienta.worker.xml.SafeXml;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Адаптер Personio (§91) — системы найма малого и среднего бизнеса DACH. Источник — XML-лента
 * вакансий {@code https://<аккаунт>.jobs.personio.<de|com>/xml}: работодатель сам включает её в
 * настройках карьерной страницы для размещения вакансий на других сайтах; ключ не нужен
 * (https://developer.personio.de/docs/retrieving-open-job-positions).
 *
 * <p>Лента отдаёт все открытые вакансии одним документом, с описанием; постраничности и
 * отдельной детали нет — {@link #getPosting} перечитывает ленту и берёт нужную вакансию. Страны
 * в ленте нет, только офис (город) — поэтому гейт рынка и отбор сбора идут по офисам
 * ({@link #reportsCountries()} = {@code true}, {@link PostingsPage#locationCounts()}). Зарплаты
 * в ленте нет. Разбор XML — через {@link SafeXml} (без DOCTYPE и внешних сущностей).</p>
 */
@Component
public class PersonioAdapter implements SourceAdapter {

    /** Код провайдера; совпадает с {@code Provider.code} источника. */
    public static final String PROVIDER_CODE = "personio";

    private final SourceHttpClient httpClient;

    /**
     * @param httpClient единый HTTP-клиент воркера (SSRF, темп на домен, потолок тела)
     */
    public PersonioAdapter(SourceHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Вакансия ленты.
     *
     * @param id          идентификатор Personio
     * @param name        заголовок
     * @param subcompany  юрлицо работодателя (как указано в аккаунте)
     * @param offices     офисы: основной первым, затем дополнительные
     * @param description текст описания без разметки или {@code null}
     */
    record Position(String id, String name, String subcompany, List<String> offices, String description) {
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    /** Офисы вакансий есть в ленте — гейт рынка по ним (§58, §91). */
    @Override
    public boolean reportsCountries() {
        return true;
    }

    @Override
    public PostingsPage listPostings(Source source, String cursor) {
        return listPostings(source, cursor, MarketScope.ALL);
    }

    /**
     * Вся лента одним запросом. Область рынка применяется на нашей стороне: остаются вакансии,
     * у которых хотя бы один офис на рынке. {@code locationCounts} — по всей ленте (для гейта).
     *
     * @param source источник (доска)
     * @param cursor не используется: страница одна
     * @param scope  область рынка
     * @return вакансии и распределение по офисам
     */
    @Override
    public PostingsPage listPostings(Source source, String cursor, MarketScope scope) {
        String baseUrl = baseUrl(source);
        List<Position> positions = parse(httpClient.getBody(baseUrl + "/xml"));
        Map<String, Integer> officeCounts = new LinkedHashMap<>();
        List<DiscoveredPosting> postings = new ArrayList<>();
        for (Position position : positions) {
            position.offices().forEach(office -> officeCounts.merge(office, 1, Integer::sum));
            if (!scope.restricted() || position.offices().stream().anyMatch(scope.isMarketLocation())) {
                postings.add(new DiscoveredPosting(position.id(), baseUrl + "/job/" + position.id(),
                        position.name(), position.offices().isEmpty() ? null : position.offices().get(0)));
            }
        }
        return new PostingsPage(postings, null, Map.of(), officeCounts);
    }

    /**
     * Деталь вакансии: лента перечитывается целиком (отдельной детали у Personio нет).
     *
     * @throws IllegalStateException если вакансии больше нет в ленте
     */
    @Override
    public FetchedPosting getPosting(Source source, String externalId) {
        String body = httpClient.getBody(baseUrl(source) + "/xml");
        Document document = SafeXml.parseDom(body);
        for (Element element : children(document.getDocumentElement(), "position")) {
            if (externalId.equals(text(element, "id"))) {
                Position position = position(element);
                List<String> offices = position.offices();
                FetchedPosting.SourceLocation location = new FetchedPosting.SourceLocation(
                        offices.isEmpty() ? null : offices.get(0), null, null,
                        offices.isEmpty() ? List.of() : offices.subList(1, offices.size()));
                return new FetchedPosting(location, FetchedPosting.SourcePay.NONE, position.description(),
                        postedOn(text(element, "createdAt")));
            }
        }
        throw new IllegalStateException("Personio: вакансии " + externalId + " нет в ленте " + source.getExternalRef());
    }

    /**
     * Сведения о доске для проверки принадлежности (A2): владелец — имя аккаунта, описание —
     * юрлица из ленты. Лента пуста — описания нет, и гейт отправит доску на ручную проверку.
     */
    @Override
    public Optional<BoardProfile> boardProfile(Source source) {
        Set<String> companies = new LinkedHashSet<>();
        parse(httpClient.getBody(baseUrl(source) + "/xml")).stream()
                .map(Position::subcompany).filter(name -> name != null && !name.isBlank())
                .forEach(companies::add);
        return Optional.of(new BoardProfile(source.getExternalRef(), String.join("; ", companies)));
    }

    /**
     * Разбор ленты.
     *
     * @param xml документ {@code <workzag-jobs><position>…</position>…</workzag-jobs>}
     * @return вакансии в порядке ленты
     * @throws IllegalStateException если документ не разбирается
     */
    static List<Position> parse(String xml) {
        Document document = SafeXml.parseDom(xml);
        return children(document.getDocumentElement(), "position").stream().map(PersonioAdapter::position).toList();
    }

    private static Position position(Element element) {
        List<String> offices = new ArrayList<>();
        Optional.ofNullable(text(element, "office")).ifPresent(offices::add);
        child(element, "additionalOffices").ifPresent(additional -> children(additional, "office").stream()
                .map(office -> office.getTextContent().strip()).filter(office -> !office.isEmpty())
                .filter(office -> !offices.contains(office)).forEach(offices::add));
        return new Position(text(element, "id"), text(element, "name"), text(element, "subcompany"),
                List.copyOf(offices), description(element));
    }

    /** Разделы описания «заголовок: текст», разметка снята (Jsoup); нет разделов — {@code null}. */
    private static String description(Element element) {
        List<String> sections = child(element, "jobDescriptions").stream()
                .flatMap(descriptions -> children(descriptions, "jobDescription").stream())
                .map(section -> Stream.of(text(section, "name"), htmlText(text(section, "value")))
                        .filter(part -> part != null && !part.isEmpty())
                        .reduce((title, body) -> title + ": " + body).orElse(""))
                .filter(section -> !section.isEmpty())
                .toList();
        return sections.isEmpty() ? null : String.join("\n", sections);
    }

    private static String htmlText(String html) {
        return html == null ? null : Jsoup.parse(html).text().strip();
    }

    /** Дата публикации из {@code createdAt} (ISO-8601 со смещением); не разобрана — {@code null}. */
    static LocalDate postedOn(String createdAt) {
        if (createdAt == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(createdAt).toLocalDate();
        } catch (DateTimeParseException unparsed) {
            return null;
        }
    }

    private static String baseUrl(Source source) {
        String baseUrl = source.getBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Текст прямого дочернего элемента или {@code null}, если его нет или он пуст. */
    private static String text(Element parent, String tag) {
        return child(parent, tag).map(element -> element.getTextContent().strip())
                .filter(value -> !value.isEmpty()).orElse(null);
    }

    private static Optional<Element> child(Element parent, String tag) {
        return children(parent, tag).stream().findFirst();
    }

    /** Прямые дочерние элементы с именем {@code tag} (вложенные глубже не берутся). */
    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && tag.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }
}
