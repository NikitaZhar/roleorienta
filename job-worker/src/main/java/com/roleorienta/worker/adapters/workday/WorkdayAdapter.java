package com.roleorienta.worker.adapters.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.FetchedPosting;
import com.roleorienta.worker.adapters.MarketScope;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.http.SourceHttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

/**
 * Адаптер энтерпрайз-системы найма Workday (де-факто открытый JSON API «cxs», §5,
 * ADR-17). Проверено на Этапе 0 (docs/stage-0-protocol.md).
 *
 * <p><b>Список — POST.</b> В отличие от Greenhouse, Workday отдаёт список вакансий
 * POST-запросом к {@code <base_url>/wday/cxs/<tenant>/<site>/jobs} с телом
 * {@code {"appliedFacets":{},"limit":20,"offset":N,"searchText":""}}. Здесь
 * {@code base_url} — хост тенанта ({@code https://<tenant>.wd<N>.myworkdayjobs.com},
 * в пилоте — адрес заглушки), а {@code Source.external_ref} = {@code "<tenant>/<site>"}
 * (сегмент пути cxs). Ответ содержит {@code total} и массив {@code jobPostings}; из
 * каждого элемента берутся {@code title} и {@code externalPath}. {@code externalPath}
 * служит идентификатором публикации в терминах источника (уникален в паре с источником)
 * и одновременно путём к детали.</p>
 *
 * <p><b>Пагинация.</b> Workday жёстко ограничивает {@code limit ≤ 20} (больше молча
 * возвращает пусто), поэтому страница фиксирована в 20, а курсор — это следующий
 * {@code offset}. Следующая страница существует, пока {@code offset + 20 < total} и
 * текущая страница непуста (защита от бесконечного цикла).</p>
 *
 * <p><b>Деталь — GET (JSON).</b> {@code <base_url>/wday/cxs/<tenant>/<site><externalPath>}
 * с {@code Accept: application/json} возвращает {@code jobPostingInfo} с локацией и
 * описанием ({@code jobDescription}, HTML). <b>Структурированной зарплаты у Workday в
 * общем случае нет</b> — зарплата и языки извлекаются из текста описания
 * детерминированным экстрактором в следующем срезе (§6, A07/A09), поэтому у многих
 * вакансий зарплата честно останется {@code unknown} (адаптер не выдумывает диапазон).
 * Настоящая дата публикации — {@code startDate} из детали, а не строка {@code postedOn}
 * из списка (A06/A18); её обработка — вместе с нормализацией дат.</p>
 *
 * <p>Все исходящие запросы идут через единый {@link SourceHttpClient} (SSRF-контур
 * §9, A13); адаптер не создаёт собственных HTTP-клиентов.</p>
 */
@Component
public class WorkdayAdapter implements SourceAdapter {

    /** Код провайдера Workday; должен совпадать с {@code Provider.code} источника. */
    public static final String PROVIDER_CODE = "workday";

    /**
     * Язык ответа списка. Workday локализует названия фасетов по {@code Accept-Language}
     * (проверено 2026-09-23: {@code de-DE} → «Vereinigte Staaten»), а гейт рынка сверяет
     * страны по английскому названию (§56) — поэтому язык фиксирован.
     */
    private static final Map<String, String> LIST_HEADERS = Map.of("Accept-Language", "en-US");

    /** Фасет со странами: {@code Location_Country} (встречается и {@code locationCountry}). */
    private static final Pattern COUNTRY_FACET = Pattern.compile("(?i).*country.*");

    /** Фасет с локациями: {@code locations} (внутри группы {@code locationMainGroup}, §59). */
    private static final Pattern LOCATIONS_FACET = Pattern.compile("(?i)locations?");

    /** Жёсткий потолок размера страницы Workday: значения выше молча дают пустой ответ. */
    private static final int PAGE_LIMIT = 20;

    private final SourceHttpClient httpClient;

    /** Разбор/сборка JSON. Создаётся локально (как в других адаптерах), потокобезопасен. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param httpClient единый HTTP-клиент сбора (SSRF-защита §9)
     */
    public WorkdayAdapter(SourceHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public boolean reportsCountries() {
        return true;
    }

    @Override
    public PostingsPage listPostings(Source source, String cursor) {
        int offset = parseOffset(cursor);
        String response = postList(source, offset, null);
        return parseList(source, response, offset, null);
    }

    /**
     * Сбор только по рынку (§62). Workday фильтрует на своей стороне по {@code appliedFacets},
     * но id значений фасетов у каждого тенанта свои — поэтому первый вызов (курсор
     * {@code null}) сначала читает фасеты без фильтра и выбирает рыночные значения:
     * страны ({@code Location_Country}) или, если фасета стран нет (тенант с одной страной),
     * однозначно рыночные локации ({@code locations}). Затем — список с этим фильтром.
     * Рыночных значений нет — пустая страница без курсора (собирать нечего). Выбранный
     * фильтр едет в курсоре ({@code "<offset>|<facet>=<id>,<id>"}), поэтому следующие
     * страницы идут сразу с фильтром, без повторного чтения фасетов.
     */
    @Override
    public PostingsPage listPostings(Source source, String cursor, MarketScope scope) {
        if (scope == null || !scope.restricted()) {
            return listPostings(source, cursor);
        }
        AppliedFacet filter;
        int offset;
        if (cursor == null || cursor.isBlank()) {
            JsonNode facets = readTree(postList(source, 0, null)).path("facets");
            filter = marketFacet(facets, scope);
            if (filter == null) {
                return new PostingsPage(List.of(), null);
            }
            offset = 0;
        } else {
            int bar = cursor.indexOf('|');
            offset = parseOffset(bar < 0 ? cursor : cursor.substring(0, bar));
            filter = bar < 0 ? null : AppliedFacet.parse(cursor.substring(bar + 1));
        }
        return parseList(source, postList(source, offset, filter), offset, filter);
    }

    /**
     * Фильтр рынка из фасетов ответа: сначала страны, иначе локации. {@code null} — на
     * рынке ничего нет.
     */
    static AppliedFacet marketFacet(JsonNode facets, MarketScope scope) {
        AppliedFacet byCountry = collectFacetIds(facets, COUNTRY_FACET, scope.isMarketCountry());
        if (byCountry != null) {
            return byCountry;
        }
        return collectFacetIds(facets, LOCATIONS_FACET, scope.isMarketLocation());
    }

    /**
     * id значений фасета (по шаблону {@code facetParameter}, с учётом вложенных групп), чей
     * {@code descriptor} подходит под предикат. {@code null} — фасета нет или подходящих
     * значений нет.
     */
    static AppliedFacet collectFacetIds(JsonNode facets, Pattern facetParameter,
                                        java.util.function.Predicate<String> accept) {
        if (!facets.isArray()) {
            return null;
        }
        for (JsonNode facet : facets) {
            String parameter = facet.path("facetParameter").asText("");
            JsonNode values = facet.path("values");
            if (facetParameter.matcher(parameter).matches()) {
                List<String> ids = new ArrayList<>();
                for (JsonNode value : values) {
                    String id = textOrNull(value.path("id"));
                    String descriptor = textOrNull(value.path("descriptor"));
                    if (id != null && descriptor != null && accept.test(descriptor.strip())) {
                        ids.add(id);
                    }
                }
                if (!ids.isEmpty()) {
                    return new AppliedFacet(parameter, List.copyOf(ids));
                }
            } else {
                AppliedFacet nested = collectFacetIds(values, facetParameter, accept);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * Фильтр Workday {@code appliedFacets}: параметр фасета и выбранные id.
     *
     * @param parameter {@code facetParameter} (напр. {@code Location_Country}, {@code locations})
     * @param ids       id значений
     */
    record AppliedFacet(String parameter, List<String> ids) {

        String encode() {
            return parameter + "=" + String.join(",", ids);
        }

        static AppliedFacet parse(String encoded) {
            int eq = encoded.indexOf('=');
            if (eq <= 0 || eq == encoded.length() - 1) {
                return null;
            }
            return new AppliedFacet(encoded.substring(0, eq), List.of(encoded.substring(eq + 1).split(",")));
        }
    }

    private String postList(Source source, int offset, AppliedFacet filter) {
        return httpClient.postJson(cxsPath(source) + "/jobs", requestBody(offset, filter), LIST_HEADERS);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать список Workday", e);
        }
    }

    /**
     * Тело POST-запроса списка: {@code {"appliedFacets":{…},"limit":20,"offset":N,"searchText":""}};
     * фильтр рынка — {@code {"<facetParameter>":["id",…]}} (§62).
     * Собирается через {@link ObjectMapper}, чтобы не экранировать JSON вручную.
     */
    private String requestBody(int offset, AppliedFacet filter) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode applied = objectMapper.createObjectNode();
        if (filter != null) {
            com.fasterxml.jackson.databind.node.ArrayNode ids = applied.putArray(filter.parameter());
            filter.ids().forEach(ids::add);
        }
        body.set("appliedFacets", applied);
        body.put("limit", PAGE_LIMIT);
        body.put("offset", offset);
        body.put("searchText", "");
        return body.toString();
    }

    /**
     * Разбирает ответ списка: {@code jobPostings[]} → публикации общего вида, и вычисляет
     * курсор следующей страницы по {@code total}.
     *
     * @throws IllegalStateException если тело не разбирается как ожидаемый JSON
     */
    private PostingsPage parseList(Source source, String response, int offset, AppliedFacet filter) {
        try {
            JsonNode root = objectMapper.readTree(response);
            int total = root.path("total").asInt(0);
            List<DiscoveredPosting> postings = new ArrayList<>();
            for (JsonNode job : root.path("jobPostings")) {
                String externalPath = textOrNull(job.path("externalPath"));
                if (externalPath == null) {
                    continue; // без пути публикацию нельзя ни идентифицировать, ни добрать
                }
                postings.add(new DiscoveredPosting(
                        externalPath,
                        publicUrl(source, externalPath),
                        job.path("title").asText()));
            }
            int nextOffset = offset + PAGE_LIMIT;
            String nextCursor = (!postings.isEmpty() && nextOffset < total)
                    ? nextOffset + (filter == null ? "" : "|" + filter.encode()) : null;
            Map<String, Integer> countries = new LinkedHashMap<>();
            collectFacetCounts(root.path("facets"), COUNTRY_FACET, countries);
            Map<String, Integer> locations = new LinkedHashMap<>();
            collectFacetCounts(root.path("facets"), LOCATIONS_FACET, locations);
            return new PostingsPage(postings, nextCursor, countries, locations);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать список Workday", e);
        }
    }

    /**
     * Распределение публикаций по значениям фасета, чей {@code facetParameter} подходит под
     * шаблон (страны §56, локации §59). Фасет бывает на верхнем уровне или вложен в группу
     * (напр. {@code locationMainGroup}, у значений которой свои {@code facetParameter} и
     * {@code values}) — поэтому обход рекурсивный. Считаются значения с текстовым
     * {@code descriptor} и числовым {@code count}; одинаковые названия суммируются.
     * Фасета нет — карта остаётся пустой («неизвестно»).
     */
    static void collectFacetCounts(JsonNode facets, Pattern facetParameter, Map<String, Integer> into) {
        if (!facets.isArray()) {
            return;
        }
        for (JsonNode facet : facets) {
            String parameter = facet.path("facetParameter").asText("");
            JsonNode values = facet.path("values");
            if (facetParameter.matcher(parameter).matches()) {
                for (JsonNode value : values) {
                    if (value.path("descriptor").isTextual() && value.path("count").isNumber()) {
                        into.merge(value.path("descriptor").asText().strip(),
                                value.path("count").asInt(), Integer::sum);
                    }
                }
            } else {
                collectFacetCounts(values, facetParameter, into); // вложенная группа фасетов
            }
        }
    }

    @Override
    public FetchedPosting getPosting(Source source, String externalId) {
        String url = cxsPath(source) + externalId;
        String response = httpClient.getJson(url);
        return parseDetail(response);
    }

    /**
     * Разбирает деталь ({@code jobPostingInfo}): локация и описание (HTML → текст), а также
     * структурные поля (§65, проверено на живых тенантах 2026-09-23): {@code country.descriptor}
     * (страна основной локации), {@code remoteType} (есть не у всех — напр. {@code Hybrid}),
     * {@code startDate} (дата публикации {@code YYYY-MM-DD}; {@code postedOn} — лишь
     * «Posted 30+ Days Ago»), {@code additionalLocations} (массив строк у многолокационных).
     * Зарплата не разбирается (у Workday нет структурного поля) — {@code compensation}
     * и {@code rawCompensation} остаются {@code null}, зарплата извлекается из текста
     * описания в следующем срезе (§6).
     *
     * @throws IllegalStateException если тело не разбирается как ожидаемый JSON
     */
    private FetchedPosting parseDetail(String response) {
        try {
            JsonNode info = objectMapper.readTree(response).path("jobPostingInfo");
            String rawLocation = textOrNull(info.path("location"));
            String rawDescription = descriptionText(info.path("jobDescription"));
            List<String> additional = new ArrayList<>();
            for (JsonNode location : info.path("additionalLocations")) {
                String value = textOrNull(location);
                if (value != null) {
                    additional.add(value.strip());
                }
            }
            return new FetchedPosting(rawLocation, null, null, rawDescription,
                    textOrNull(info.path("country").path("descriptor")),
                    textOrNull(info.path("remoteType")),
                    parseDate(textOrNull(info.path("startDate"))),
                    additional);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать деталь Workday", e);
        }
    }

    /** Дата {@code YYYY-MM-DD} или {@code null}, если поля нет или формат иной. */
    private static java.time.LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(value.strip());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Базовый путь cxs без хвоста: {@code <base_url>/wday/cxs/<external_ref>}, где
     * {@code external_ref} = {@code "<tenant>/<site>"}. Хвостовые слэши снимаются.
     */
    private String cxsPath(Source source) {
        String baseUrl = stripTrailingSlash(source.getBaseUrl());
        String ref = trimSlashes(source.getExternalRef());
        return baseUrl + "/wday/cxs/" + ref;
    }

    /**
     * Человекочитаемая ссылка на публикацию: {@code <base_url>/en-US/<site><externalPath>}.
     * Язык витрины фиксирован английским (паспорт пилота — «пока только английский»);
     * точная локализация ссылки — следующий срез вместе с мультиязычием.
     */
    private String publicUrl(Source source, String externalPath) {
        String baseUrl = stripTrailingSlash(source.getBaseUrl());
        String site = siteOf(source.getExternalRef());
        return baseUrl + "/en-US/" + site + externalPath;
    }

    /** Сегмент {@code site} из {@code external_ref} = {@code "<tenant>/<site>"}. */
    private String siteOf(String externalRef) {
        String ref = trimSlashes(externalRef);
        int slash = ref.indexOf('/');
        return slash >= 0 ? ref.substring(slash + 1) : ref;
    }

    private int parseOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor.trim());
            return Math.max(offset, 0);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Некорректный курсор Workday: " + cursor, e);
        }
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Снимает HTML-разметку с описания и возвращает текст. Пустой узел/текст → {@code null}
     * (явное «нет описания»).
     */
    private String descriptionText(JsonNode content) {
        if (content.isMissingNode() || content.isNull()) {
            return null;
        }
        String text = Jsoup.parse(content.asText()).text().strip();
        return text.isEmpty() ? null : text;
    }

    /** Текст узла или {@code null}, если узел отсутствует/пуст (явное «неизвестно»). */
    private static String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }
}
