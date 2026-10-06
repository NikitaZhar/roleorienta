package com.roleorienta.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Клиент приёмочных тестов API как SPA: хранит cookie (сессия, CSRF-токен) и возвращает токен заголовком
 * {@code X-XSRF-TOKEN} в изменяющих запросах; {@code If-Match} — если задан.
 */
final class ApiTestClient {

    /** Пароль тестовых учётных записей. */
    static final String PASSWORD = "secret-password";

    private static final Pattern COOKIE = Pattern.compile("^([^=]+)=([^;]*)");

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final int port;
    /** Изменяющие запросы без CSRF-токена. */
    boolean withoutCsrf;
    /** Заголовок {@code If-Match} для {@code PUT}; {@code null} — без него. */
    String ifMatch;

    /**
     * @param port порт приложения теста
     */
    ApiTestClient(int port) {
        this.port = port;
    }

    /**
     * @param port  порт приложения теста
     * @param email email новой учётной записи
     * @return клиент после регистрации и входа
     * @throws IOException          ошибка HTTP
     * @throws InterruptedException прерывание
     */
    static ApiTestClient signedIn(int port, String email) throws IOException, InterruptedException {
        ApiTestClient client = new ApiTestClient(port);
        client.send("POST", "/api/v1/auth/register", credentials(email));
        assertThat(client.send("POST", "/api/v1/auth/login", credentials(email)).status()).isEqualTo(200);
        return client;
    }

    /**
     * @param email email
     * @return тело запроса регистрации и входа
     */
    static String credentials(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    /**
     * @param method метод
     * @param path   путь
     * @param body   тело JSON; {@code null} — без тела
     * @return ответ
     * @throws IOException          ошибка HTTP
     * @throws InterruptedException прерывание
     */
    Response send(String method, String path, String body) throws IOException, InterruptedException {
        if (!"GET".equals(method) && !withoutCsrf && !cookies.containsKey("XSRF-TOKEN")) {
            send("GET", "/api/v1/countries", null);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (!cookies.isEmpty()) {
            request.header("Cookie", String.join("; ", cookies.entrySet().stream()
                    .map(cookie -> cookie.getKey() + "=" + cookie.getValue()).toList()));
        }
        if (!"GET".equals(method) && !withoutCsrf) {
            request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        if (ifMatch != null && "PUT".equals(method)) {
            request.header("If-Match", ifMatch);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        for (String setCookie : response.headers().allValues("set-cookie")) {
            Matcher cookie = COOKIE.matcher(setCookie);
            if (cookie.find()) {
                if (cookie.group(2).isEmpty() || setCookie.contains("Max-Age=0")) {
                    cookies.remove(cookie.group(1));
                } else {
                    cookies.put(cookie.group(1), cookie.group(2));
                }
            }
        }
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("etag").orElse(null));
    }

    /**
     * Ответ: код, тело, {@code ETag}.
     *
     * @param status код
     * @param body   тело
     * @param etag   заголовок {@code ETag}; {@code null} — нет
     */
    record Response(int status, String body, String etag) {
    }
}
