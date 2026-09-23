package com.roleorienta.worker.adapters.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.FetchedPosting;
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
    public PostingsPage listPostings(Source source, String cursor) {
        int offset = parseOffset(cursor);
        String url = cxsPath(source) + "/jobs";
        String body = requestBody(offset);
        String response = httpClient.postJson(url, body, LIST_HEADERS);
        return parseList(source, response, offset);
    }

    /**
     * Тело POST-запроса списка: {@code {"appliedFacets":{},"limit":20,"offset":N,"searchText":""}}.
     * Собирается через {@link ObjectMapper}, чтобы не экранировать JSON вручную.
     */
    private String requestBody(int offset) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("appliedFacets", objectMapper.createObjectNode());
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
    private PostingsPage parseList(Source source, String response, int offset) {
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
                    ? String.valueOf(nextOffset) : null;
            Map<String, Integer> countries = new LinkedHashMap<>();
            collectCountryCounts(root.path("facets"), countries);
            return new PostingsPage(postings, nextCursor, countries);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать список Workday", e);
        }
    }

    /**
     * Распределение публикаций по странам из фасетов ответа списка (§56). Фасет стран
     * ({@code facetParameter} содержит «country») бывает на верхнем уровне или вложен в
     * группу (напр. {@code locationMainGroup}, у значений которой свои {@code facetParameter}
     * и {@code values}) — поэтому обход рекурсивный. Считаются значения с текстовым
     * {@code descriptor} и числовым {@code count}; одинаковые названия суммируются.
     * Фасетов нет — карта остаётся пустой («неизвестно»).
     */
    static void collectCountryCounts(JsonNode facets, Map<String, Integer> into) {
        if (!facets.isArray()) {
            return;
        }
        for (JsonNode facet : facets) {
            String parameter = facet.path("facetParameter").asText("");
            JsonNode values = facet.path("values");
            if (COUNTRY_FACET.matcher(parameter).matches()) {
                for (JsonNode value : values) {
                    if (value.path("descriptor").isTextual() && value.path("count").isNumber()) {
                        into.merge(value.path("descriptor").asText().strip(),
                                value.path("count").asInt(), Integer::sum);
                    }
                }
            } else {
                collectCountryCounts(values, into); // вложенная группа фасетов
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
     * Разбирает деталь ({@code jobPostingInfo}): локация и описание (HTML → текст).
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
            return new FetchedPosting(rawLocation, null, null, rawDescription);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать деталь Workday", e);
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
    private String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }
}
