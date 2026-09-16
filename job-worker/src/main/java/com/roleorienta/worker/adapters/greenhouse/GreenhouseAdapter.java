package com.roleorienta.worker.adapters.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.FetchedPosting;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.http.SourceHttpClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Адаптер системы найма Greenhouse (публичный Job Board API, JSON).
 *
 * <p>Лента доски отдаётся одним ответом по адресу
 * {@code <base_url>/v1/boards/<slug>/jobs?content=true}, где {@code slug} — это
 * {@code Source.external_ref}, а {@code base_url} берётся из источника (в пилоте —
 * адрес заглушки WireMock). Ответ содержит массив {@code jobs}; из каждого элемента
 * берутся {@code id}, {@code title} и {@code absolute_url}. Структурированные
 * зарплатные диапазоны Greenhouse отдаёт на detail-endpoint (§5, contract-карточка
 * A12) — это относится к заданию {@code FETCH_POSTING} следующего инкремента.</p>
 *
 * <p>Доска Greenhouse не пагинируется (весь список — в одном ответе), поэтому
 * страница одна и {@code nextCursor} всегда {@code null}. Отдельного detail-вызова
 * на каждую публикацию не делается (§5: «деталь из сохранённого ответа, а не N
 * перекачек»): заголовок и ссылка уже есть в списке.</p>
 */
@Component
public class GreenhouseAdapter implements SourceAdapter {

    /** Код провайдера Greenhouse; должен совпадать с {@code Provider.code} источника. */
    public static final String PROVIDER_CODE = "greenhouse";

    private final SourceHttpClient httpClient;

    /** Разбор JSON. Создаётся локально (как в {@code SourceScheduler}), потокобезопасен. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param httpClient HTTP-клиент для чтения ленты
     */
    public GreenhouseAdapter(SourceHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public PostingsPage listPostings(Source source, String cursor) {
        String url = buildJobsUrl(source);
        String body = httpClient.getBody(url);
        List<DiscoveredPosting> postings = parseJobs(body);
        return new PostingsPage(postings, null);
    }

    /**
     * Строит адрес ленты доски: {@code <base_url>/v1/boards/<slug>/jobs?content=true}.
     * Хвостовой слэш в {@code base_url} снимается, чтобы адрес не удвоился.
     */
    private String buildJobsUrl(Source source) {
        String baseUrl = source.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/v1/boards/" + source.getExternalRef() + "/jobs?content=true";
    }

    /**
     * Разбирает тело ответа Greenhouse в список публикаций общего вида.
     *
     * @param body тело JSON-ответа
     * @return публикации из массива {@code jobs}
     * @throws IllegalStateException если тело не разбирается как ожидаемый JSON
     */
    private List<DiscoveredPosting> parseJobs(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode jobs = root.path("jobs");
            List<DiscoveredPosting> result = new ArrayList<>();
            for (JsonNode job : jobs) {
                result.add(new DiscoveredPosting(
                        job.path("id").asText(),
                        job.path("absolute_url").asText(),
                        job.path("title").asText()));
            }
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать ответ Greenhouse", e);
        }
    }

    @Override
    public FetchedPosting getPosting(Source source, String externalId) {
        String url = buildJobUrl(source, externalId);
        String body = httpClient.getBody(url);
        return parseDetail(body);
    }

    /**
     * Строит адрес детальной страницы:
     * {@code <base_url>/v1/boards/<slug>/jobs/<id>?pay_transparency=true}. Флаг
     * {@code pay_transparency=true} нужен, чтобы Greenhouse включил зарплатные
     * диапазоны, которых нет в ленте-списке (§5, contract-карточка A12).
     */
    private String buildJobUrl(Source source, String externalId) {
        String baseUrl = source.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/v1/boards/" + source.getExternalRef() + "/jobs/" + externalId
                + "?pay_transparency=true";
    }

    /**
     * Разбирает деталь публикации: локация ({@code location.name}) и сырая строка
     * зарплаты из первого диапазона {@code pay_input_ranges}. Нормализация не делается.
     */
    private FetchedPosting parseDetail(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String rawLocation = textOrNull(root.path("location").path("name"));
            String rawCompensation = firstPayRange(root.path("pay_input_ranges"));
            return new FetchedPosting(rawLocation, rawCompensation);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать деталь Greenhouse", e);
        }
    }

    /** Текст узла или {@code null}, если узел отсутствует/пуст (явное «неизвестно»). */
    private String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /**
     * Формирует сырую строку зарплаты из первого диапазона {@code pay_input_ranges}
     * (Greenhouse отдаёт суммы в центах). Это НЕ нормализация: значения не приводятся
     * к общей валюте/периоду, gross/net не различается — только читаемое сведение
     * исходных чисел. Полная нормализация зарплат — отдельный срез (§6, A09).
     */
    private String firstPayRange(JsonNode ranges) {
        if (!ranges.isArray() || ranges.size() == 0) {
            return null;
        }
        JsonNode range = ranges.get(0);
        long minUnits = range.path("min_cents").asLong(0) / 100;
        long maxUnits = range.path("max_cents").asLong(0) / 100;
        String currency = textOrNull(range.path("currency_type"));
        String title = textOrNull(range.path("title"));
        StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append(title).append(": ");
        }
        sb.append(minUnits).append("–").append(maxUnits);
        if (currency != null) {
            sb.append(' ').append(currency);
        }
        return sb.toString();
    }
}
