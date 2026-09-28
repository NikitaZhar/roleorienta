package com.roleorienta.worker.adapter.greenhouse;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Заглушка Greenhouse Job Board API на встроенном в JDK сервере: отдаёт заданный тест-кодом ответ
 * на {@code /v1/boards/{board}/jobs}.
 */
public final class GreenhouseStub implements AutoCloseable {

    /** Доска, которую обслуживает заглушка. */
    public static final String BOARD = "acme";

    private static final int STATUS_OK = 200;

    private final HttpServer server;
    private final AtomicInteger status = new AtomicInteger(STATUS_OK);
    private final AtomicReference<String> body = new AtomicReference<>("{\"jobs\":[]}");

    /**
     * Запускает заглушку на свободном порту.
     */
    public GreenhouseStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.createContext("/v1/boards/" + BOARD + "/jobs", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    /**
     * Ответ списком вакансий: каждая вакансия — пара «id, title».
     *
     * @param idsAndTitles чередующиеся id и названия
     */
    public void respondWithJobs(String... idsAndTitles) {
        StringBuilder json = new StringBuilder("{\"jobs\":[");
        for (int index = 0; index < idsAndTitles.length; index += 2) {
            if (index > 0) {
                json.append(',');
            }
            String id = idsAndTitles[index];
            json.append("{\"id\":").append(id)
                    .append(",\"title\":\"").append(idsAndTitles[index + 1])
                    .append("\",\"absolute_url\":\"https://job-boards.greenhouse.io/acme/jobs/").append(id)
                    .append("\",\"location\":{\"name\":\"Bratislava\"},\"content\":\"&lt;p&gt;Text&lt;/p&gt;\"}");
        }
        respond(STATUS_OK, json.append("]}").toString());
    }

    /**
     * Произвольный ответ.
     *
     * @param newStatus код ответа
     * @param newBody   тело
     */
    public void respond(int newStatus, String newBody) {
        status.set(newStatus);
        body.set(newBody);
    }

    /**
     * @return базовый адрес заглушки для {@code app.adapter.greenhouse.base-url}
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
