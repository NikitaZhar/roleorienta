package com.roleorienta.worker.adapter.nalgoo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Адаптер Nalgoo — словацкой системы найма (BILLA, Foxconn, Markíza; стенограмма §58, образцы
 * {@code docs/samples/nalgoo-*}). Кадровые сайты Nalgoo ({@code billa.nalgoo-jobs.com}, {@code kariera.foxconn.sk})
 * берут вакансии из публичного API, тем же API пользуется и адаптер (адрес найден в скриптах сайта; robots.txt у
 * {@code ats.nalgoo.com} нет).
 *
 * <ul>
 *   <li>Доска — имя организации в Nalgoo ({@code billa}).</li>
 *   <li>Список — {@code GET <api>/organizations/<организация>/jobs}: JSON-массив всех вакансий одним ответом —
 *       {@code id}, {@code title}, {@code url}, {@code location} (город, без страны: страну работы определяет
 *       общий разбор мест). Фильтра страны нет: доска — одного работодателя.</li>
 *   <li>Текст — {@code GET <api>/organizations/<организация>/jobs/<id>}, поле {@code content} (HTML).</li>
 *   <li>Отказ или неразобранный ответ — источник недоступен (чтение одним запросом).</li>
 * </ul>
 */
@Component
public class NalgooAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "nalgoo";

    private static final Pattern BOARD = Pattern.compile("[a-z0-9-]+");
    private static final Pattern JOB_ID = Pattern.compile("\\d{1,12}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(NalgooAdapter.class);

    private final ExternalHttpClient httpClient;
    private final NalgooProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес API
     */
    public NalgooAdapter(ExternalHttpClient httpClient, NalgooProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SourceReadResult read(String board) {
        if (!BOARD.matcher(board).matches()) {
            return new SourceReadResult.Unavailable(new HttpResult.PermanentFailure(
                    HttpResult.Kind.BLOCKED, "Not a Nalgoo organization: " + board));
        }
        HttpResult result = httpClient.get(URI.create(jobsUrl(board)));
        if (!(result instanceof HttpResult.Success success)) {
            return new SourceReadResult.Unavailable(result);
        }
        try {
            JsonNode jobs = JSON.readTree(success.body());
            if (!jobs.isArray()) {
                return malformed("not an array");
            }
            List<FetchedPosting> postings = new ArrayList<>();
            for (JsonNode job : jobs) {
                String id = job.path("id").asText();
                if (JOB_ID.matcher(id).matches()) {
                    postings.add(new FetchedPosting(id, job.path("title").asText(), job.path("url").asText(),
                            text(job.path("location")), null));
                }
            }
            return SourceReadResult.Read.full(postings, List.of(success.body()));
        } catch (JsonProcessingException exception) {
            return malformed(exception.getOriginalMessage());
        }
    }

    /**
     * Текст и место из вакансии ({@code content}, {@code location}).
     */
    @Override
    public FetchedPosting detail(String board, String externalId) {
        if (!BOARD.matcher(board).matches() || !JOB_ID.matcher(externalId).matches()) {
            return null;
        }
        if (!(httpClient.get(URI.create(jobsUrl(board) + "/" + externalId)) instanceof HttpResult.Success success)) {
            return null;
        }
        try {
            JsonNode job = JSON.readTree(success.body());
            return new FetchedPosting(externalId, null, job.path("url").asText(null), text(job.path("location")),
                    text(job.path("content")));
        } catch (JsonProcessingException exception) {
            LOG.warn("Nalgoo posting {} of {}: malformed answer", externalId, board);
            return null;
        }
    }

    private String jobsUrl(String board) {
        return properties.apiUrl() + "/organizations/" + board + "/jobs";
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().strip() : null;
    }

    private static SourceReadResult malformed(String reason) {
        return new SourceReadResult.Unavailable(
                new HttpResult.TemporaryFailure("Malformed Nalgoo response: " + reason, Duration.ZERO));
    }
}
