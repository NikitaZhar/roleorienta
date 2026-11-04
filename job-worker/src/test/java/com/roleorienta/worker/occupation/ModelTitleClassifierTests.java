package com.roleorienta.worker.occupation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Обращение к локальной языковой модели (§262) на заглушке чата Ollama: модель, без потока, {@code temperature} 0 и
 * контекст в настройках, каталог — системным сообщением, названия — нумерованными строками; ответ «номер — код»
 * разбирается, «-» — не распознано, код вне каталога и номер вне пачки отбрасываются; ответ не 200 — недоступна.
 */
class ModelTitleClassifierTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<TitleClassifier.Group> GROUPS = List.of(
            new TitleClassifier.Group("2512", "Software developers"),
            new TitleClassifier.Group("8322", "Car, taxi and van drivers"));

    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "";
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/chat", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsCatalogAndTitlesAndParsesCodes() throws IOException {
        answer = "1\t2512\n2\t-\n3\t9999\n7\t8322\nNote: done";

        Map<String, String> codes = classifier().classify(
                List.of("ServiceNow Developer", "Zxqw - zmena A", "Pilot"), GROUPS);

        assertThat(codes).containsExactlyInAnyOrderEntriesOf(Map.of("ServiceNow Developer", "2512",
                "Zxqw - zmena A", ""));
        JsonNode body = JSON.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("test-model");
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        assertThat(body.path("options").path("temperature").asInt(-1)).isZero();
        assertThat(body.path("options").path("num_ctx").asInt()).isEqualTo(8192);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(0).path("content").asText()).contains("2512\tSoftware developers");
        assertThat(body.path("messages").path(1).path("content").asText())
                .isEqualTo("1\tServiceNow Developer\n2\tZxqw - zmena A\n3\tPilot\n");
    }

    @Test
    void refusalMeansUnavailable() {
        status = 503;

        assertThatThrownBy(() -> classifier().classify(List.of("Vodič"), GROUPS))
                .isInstanceOf(TitleClassifier.ClassifierUnavailable.class).hasMessageContaining("503");
    }

    private ModelTitleClassifier classifier() {
        return new ModelTitleClassifier(new TitleClassificationProperties(true, "test-model",
                new TitleClassificationProperties.Api("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/api/chat", Duration.ofSeconds(5), 8192),
                new TitleClassificationProperties.Limits(20, 10, 2)));
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = JSON.createObjectNode().set("message", JSON.createObjectNode().put("role", "assistant")
                .put("content", answer)).toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
