package com.roleorienta.worker.occupation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Векторы текстов от модели Ollama на том же компьютере ({@code POST /api/embed}; стенограмма §263). Вектор — список
 * чисел (у bge-m3 — 1 024), который модель ставит тексту так, что близкие по смыслу тексты на любом языке получают
 * близкие векторы; сходство двух текстов — скалярное произведение их векторов длины 1 (косинус угла: 1 — одно и то
 * же, около 0 — не связаны). https://github.com/ollama/ollama/blob/main/docs/api.md#generate-embeddings
 *
 * <p>Запрос — {@link HttpClient} JDK, а не {@code ExternalHttpClient} ядра: это не чтение чужого сайта, а своя
 * программа на {@code localhost}. Тексты уходят пачками ({@code api.batch-size}). Ответ не HTTP 200, Ollama не
 * запущена, не ответила в срок или вернула не столько векторов, сколько текстов — {@link
 * TitleClassifier.ClassifierUnavailable}: задание повторится позже.</p>
 */
@Component
public class OllamaEmbedder {

    private static final int OK = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TitleClassificationProperties properties;
    private final HttpClient http;

    /**
     * @param properties модель, адрес Ollama, срок ответа, размер пачки
     */
    public OllamaEmbedder(TitleClassificationProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.api().timeout()).build();
    }

    /**
     * @param texts тексты
     * @return вектор длины 1 для каждого текста, в том же порядке
     * @throws TitleClassifier.ClassifierUnavailable Ollama недоступна или ответила не так
     */
    public float[][] embed(List<String> texts) {
        float[][] vectors = new float[texts.size()][];
        int batchSize = properties.api().batchSize();
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            JsonNode embeddings = request(batch);
            if (!embeddings.isArray() || embeddings.size() != batch.size()) {
                throw new TitleClassifier.ClassifierUnavailable("Embedding answer has no vector for every text");
            }
            for (int index = 0; index < batch.size(); index++) {
                vectors[from + index] = unit(embeddings.get(index));
            }
        }
        return vectors;
    }

    private JsonNode request(List<String> batch) {
        ObjectNode body = JSON.createObjectNode().put("model", properties.model());
        batch.forEach(body.putArray("input")::add);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.api().endpoint()))
                .timeout(properties.api().timeout())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != OK) {
                throw new TitleClassifier.ClassifierUnavailable("Embedding answered HTTP " + response.statusCode());
            }
            return JSON.readTree(response.body()).path("embeddings");
        } catch (IOException failure) {
            throw new TitleClassifier.ClassifierUnavailable("Embedding request failed: "
                    + failure.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TitleClassifier.ClassifierUnavailable("Embedding request interrupted");
        }
    }

    /**
     * Вектор, приведённый к длине 1 (Ollama обычно так и отдаёт; приведение не зависит от этого).
     */
    static float[] unit(JsonNode numbers) {
        float[] vector = new float[numbers.size()];
        double length = 0;
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) numbers.get(index).asDouble();
            length += vector[index] * vector[index];
        }
        if (length == 0) {
            throw new TitleClassifier.ClassifierUnavailable("Embedding answer has an empty vector");
        }
        float scale = (float) (1 / Math.sqrt(length));
        for (int index = 0; index < vector.length; index++) {
            vector[index] *= scale;
        }
        return vector;
    }
}
