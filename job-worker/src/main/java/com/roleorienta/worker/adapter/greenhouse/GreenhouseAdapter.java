package com.roleorienta.worker.adapter.greenhouse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Адаптер Greenhouse: публичный Job Board API, JSON без ключа, весь список одним ответом.
 * {@code GET /v1/boards/{board}/jobs?content=true} — поля {@code id}, {@code title},
 * {@code absolute_url}, {@code location.name}, {@code content}.
 * https://developers.greenhouse.io/job-board.html
 */
@Component
public class GreenhouseAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "greenhouse";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExternalHttpClient httpClient;
    private final GreenhouseProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес API
     */
    public GreenhouseAdapter(ExternalHttpClient httpClient, GreenhouseProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SourceReadResult read(String board) {
        URI uri = URI.create(properties.baseUrl() + "/v1/boards/"
                + URLEncoder.encode(board, StandardCharsets.UTF_8) + "/jobs?content=true");
        HttpResult result = httpClient.get(uri);
        if (!(result instanceof HttpResult.Success success)) {
            return new SourceReadResult.Unavailable(result);
        }
        try {
            return SourceReadResult.Read.full(parse(success.body()), List.of(success.body()));
        } catch (JsonProcessingException exception) {
            return new SourceReadResult.Unavailable(
                    new HttpResult.TemporaryFailure("Malformed Greenhouse response: " + exception.getOriginalMessage(),
                            Duration.ZERO));
        }
    }

    /**
     * Название работодателя — {@code name} описания доски {@code /v1/boards/<доска>} (образец
     * {@code docs/samples/gh-sentinellabs-board.json}).
     */
    @Override
    public Optional<String> employerName(String board, String externalId) {
        HttpResult result = httpClient.get(URI.create(properties.baseUrl() + "/v1/boards/"
                + URLEncoder.encode(board, StandardCharsets.UTF_8)));
        if (!(result instanceof HttpResult.Success success)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(textOrNull(JSON.readTree(success.body()).path("name")));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    private static List<FetchedPosting> parse(String body) throws JsonProcessingException {
        List<FetchedPosting> postings = new ArrayList<>();
        for (JsonNode job : JSON.readTree(body).path("jobs")) {
            postings.add(new FetchedPosting(
                    job.path("id").asText(),
                    job.path("title").asText(),
                    job.path("absolute_url").asText(),
                    textOrNull(job.path("location").path("name")),
                    textOrNull(job.path("content"))));
        }
        return postings;
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }
}
