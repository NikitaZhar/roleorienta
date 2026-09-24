package com.roleorienta.worker.adapters.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.CompensationRange;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.FetchedPosting;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.http.SourceHttpClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
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
     * Разбирает деталь публикации: локация ({@code location.name}) и первый
     * зарплатный диапазон {@code pay_input_ranges}. Возвращает и структуру
     * ({@link CompensationRange} — для нормализации), и сырую строку (для показа).
     * Greenhouse отдаёт суммы в центах — переводим в единицы валюты; нормализация
     * (период, gross/net) здесь не делается.
     */
    private FetchedPosting parseDetail(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String rawLocation = textOrNull(root.path("location").path("name"));
            String rawDescription = descriptionText(root.path("content"));

            JsonNode ranges = root.path("pay_input_ranges");
            if (!ranges.isArray() || ranges.size() == 0) {
                return new FetchedPosting(FetchedPosting.SourceLocation.of(rawLocation),
                        FetchedPosting.SourcePay.NONE, rawDescription, null);
            }
            JsonNode range = ranges.get(0);
            BigDecimal min = centsToAmount(range.path("min_cents"));
            BigDecimal max = centsToAmount(range.path("max_cents"));
            String currency = textOrNull(range.path("currency_type"));
            CompensationRange compensation = new CompensationRange(min, max, currency);
            String rawCompensation = displayCompensation(textOrNull(range.path("title")), min, max, currency);
            return new FetchedPosting(FetchedPosting.SourceLocation.of(rawLocation),
                    new FetchedPosting.SourcePay(rawCompensation, compensation), rawDescription, null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать деталь Greenhouse", e);
        }
    }

    /**
     * Снимает HTML-разметку с описания вакансии ({@code content}) и возвращает текст.
     * Greenhouse отдаёт описание как HTML; Jsoup убирает теги и декодирует сущности.
     * Пустой узел или пустой текст → {@code null} (явное «нет описания»).
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
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /** Сумма из центов в единицы валюты, либо {@code null}, если узел отсутствует. */
    private BigDecimal centsToAmount(JsonNode cents) {
        if (cents.isMissingNode() || cents.isNull()) {
            return null;
        }
        return BigDecimal.valueOf(cents.asLong()).movePointLeft(2);
    }

    /**
     * Человекочитаемая сырая строка зарплаты для показа/хранения как есть (НЕ
     * нормализация): {@code "<title>: <min>–<max> <currency>"}.
     */
    private String displayCompensation(String title, BigDecimal min, BigDecimal max, String currency) {
        StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append(title).append(": ");
        }
        sb.append(plain(min)).append("–").append(plain(max));
        if (currency != null) {
            sb.append(' ').append(currency);
        }
        return sb.toString();
    }

    /** Число без хвостовых нулей ({@code 80000.00} → {@code "80000"}), либо {@code "?"}. */
    private String plain(BigDecimal value) {
        return value == null ? "?" : value.stripTrailingZeros().toPlainString();
    }
}
