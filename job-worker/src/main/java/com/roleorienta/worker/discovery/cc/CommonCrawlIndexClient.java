package com.roleorienta.worker.discovery.cc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.http.SourceHttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Клиент индекса Common Crawl (CDX Server API) — основной вход автоматического
 * обнаружения для Workday (§5, A11, ADR-17; протокол Этапа 0 §3).
 *
 * <p><b>Почему Common Crawl, а не CT.</b> Живая проверка 2026-09-23: из 100 выпусков
 * сертификатов под {@code myworkdayjobs.com} — 140 wildcard-имён и ровно один
 * конкретный хост тенанта (Workday выпускает сертификаты {@code *.wdN.myworkdayjobs.com}),
 * то есть CT тенантов Workday не перечисляет. Индекс Common Crawl хранит <b>полные URL</b>
 * страниц карьеры ({@code https://<tenant>.wdN.myworkdayjobs.com/<locale>/<site>/job/...}),
 * поэтому даёт пару {@code tenant/site} сразу, включая нетиповые имена сайтов, которые
 * словарный {@code WorkdaySiteResolver} не угадал бы (первый срез индекса: 3000 URL →
 * 116 досок).</p>
 *
 * <p><b>API.</b> Коллекции — {@code GET <base>/collinfo.json} (первая в списке —
 * свежайшая, напр. {@code CC-MAIN-2026-39}). Поиск —
 * {@code GET <base>/<collection>-index?url=<pattern>&output=json&fl=url&page=<N>}: ответ —
 * JSON Lines, по объекту {@code {"url": ...}} на строку. Число страниц —
 * тот же запрос с {@code showNumPages=true} → {@code {"pages": N, ...}}. Результаты
 * отсортированы по SURT (обратное имя хоста), поэтому полный охват требует обхода
 * страниц по курсору {@code (collection, page)} — хранение курсора и бюджет страниц за
 * проход — задача вызывающей стороны (следующий срез). Отсутствие совпадений индекс
 * отдаёт как {@code 404} — это пустой результат, а не ошибка.</p>
 *
 * <p>Все запросы — через единый {@link SourceHttpClient} (SSRF-контур §9, A13). Условия
 * использования индекса (A24) и уважение к его лимитам (сервер перегружается и отвечает
 * 503; бюджет/rate-limit — B1) зафиксированы в протоколе Этапа 0.</p>
 */
@Component
public class CommonCrawlIndexClient {

    private final SourceHttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param httpClient единый HTTP-клиент (SSRF-контур §9)
     * @param baseUrl    база индекса (по умолчанию {@code https://index.commoncrawl.org})
     */
    public CommonCrawlIndexClient(
            SourceHttpClient httpClient,
            @Value("${app.discovery.cc.index-base-url:https://index.commoncrawl.org}") String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Свежайшая коллекция индекса.
     *
     * @return идентификатор коллекции (напр. {@code CC-MAIN-2026-39})
     * @throws IllegalStateException если список коллекций пуст или не разбирается
     */
    public String latestCollection() {
        JsonNode collections = parse(httpClient.getBody(baseUrl + "/collinfo.json"));
        String id = collections.isArray() && !collections.isEmpty()
                ? collections.get(0).path("id").asText(null)
                : null;
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Common Crawl: пустой список коллекций");
        }
        return id;
    }

    /**
     * Число страниц результата для шаблона URL в коллекции.
     *
     * @param collection идентификатор коллекции
     * @param urlPattern шаблон URL индекса (напр. {@code *.myworkdayjobs.com})
     * @return число страниц; {@code 0}, если совпадений нет
     */
    public int pageCount(String collection, String urlPattern) {
        String body = getOrEmpty(searchUrl(collection, urlPattern) + "&showNumPages=true");
        if (body.isBlank()) {
            return 0;
        }
        return Math.max(0, parse(body).path("pages").asInt(0));
    }

    /**
     * URL одной страницы результата.
     *
     * @param collection идентификатор коллекции
     * @param urlPattern шаблон URL индекса
     * @param page       номер страницы, с {@code 0}
     * @return URL в порядке индекса (повторы возможны — дедуп у вызывающего); пусто, если совпадений нет
     */
    public List<String> urlsOnPage(String collection, String urlPattern, int page) {
        String body = getOrEmpty(searchUrl(collection, urlPattern) + "&fl=url&page=" + page);
        List<String> urls = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode url = parse(line).path("url");
            if (url.isTextual() && !url.asText().isBlank()) {
                urls.add(url.asText());
            }
        }
        return urls;
    }

    private String searchUrl(String collection, String urlPattern) {
        return baseUrl + "/" + enc(collection) + "-index?url=" + enc(urlPattern) + "&output=json";
    }

    /** GET; {@code 404} индекса («No Captures found») — пустой ответ. */
    private String getOrEmpty(String url) {
        try {
            String body = httpClient.getBody(url);
            return body == null ? "" : body;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return "";
            }
            throw e;
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать ответ индекса Common Crawl", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
