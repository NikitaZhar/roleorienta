package com.roleorienta.worker.occupation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Векторы от Ollama (§263) на заглушке {@code /api/embed}: модель и тексты пачками в запросе, вектор каждого текста в
 * том же порядке и длины 1; ответ не 200 или не столько векторов, сколько текстов, — недоступна.
 */
class OllamaEmbedderTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile boolean dropOne;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/embed", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsBatchesAndReturnsUnitVectorsInOrder() {
        float[][] vectors = embedder().embed(List.of("Kuchár", "Vodič", "Krajčírka"));

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("model").asText()).isEqualTo("test-model");
        assertThat(requests.get(0).path("input").toString()).isEqualTo("[\"Kuchár\",\"Vodič\"]");
        assertThat(requests.get(1).path("input").toString()).isEqualTo("[\"Krajčírka\"]");
        assertThat(vectors[0]).containsExactly(new float[] {0.6f, 0.8f}, within(1e-6f));
        assertThat(vectors[2]).containsExactly(new float[] {0.6f, 0.8f}, within(1e-6f));
    }

    @Test
    void missingVectorMeansUnavailable() {
        dropOne = true;

        assertThatThrownBy(() -> embedder().embed(List.of("Kuchár", "Vodič")))
                .isInstanceOf(TitleClassifier.ClassifierUnavailable.class);
    }

    @Test
    void refusalMeansUnavailable() {
        status = 503;

        assertThatThrownBy(() -> embedder().embed(List.of("Kuchár")))
                .isInstanceOf(TitleClassifier.ClassifierUnavailable.class).hasMessageContaining("503");
    }

    private OllamaEmbedder embedder() {
        return new OllamaEmbedder(new TitleClassificationProperties(true, "test-model",
                new TitleClassificationProperties.Api("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/api/embed", Duration.ofSeconds(5), 2),
                new TitleClassificationProperties.Limits(10, 10, 5, 0.8)));
    }

    /** Каждому тексту — вектор (3, 4): длина 5, после приведения — (0,6; 0,8). */
    private void respond(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        requests.add(request);
        var embeddings = JSON.createArrayNode();
        int count = request.path("input").size() - (dropOne ? 1 : 0);
        for (int index = 0; index < count; index++) {
            embeddings.addArray().add(3).add(4);
        }
        byte[] bytes = JSON.createObjectNode().set("embeddings", embeddings).toString()
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
