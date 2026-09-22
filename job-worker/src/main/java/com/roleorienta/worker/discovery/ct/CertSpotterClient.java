package com.roleorienta.worker.discovery.ct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.http.SourceHttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Клиент Certificate Transparency поверх Cert Spotter Search API (SSLMate) — вход
 * автоматического обнаружения (§5, A11, ADR-17). Перечисляет поддомены заданного
 * домена вендора (напр. {@code myworkdayjobs.com}) по публичным CT-логам.
 *
 * <p>Выбор Cert Spotter вместо {@code crt.sh}: последний закрывает автоматических
 * клиентов через {@code robots.txt}; Cert Spotter отдаёт документированный JSON-API.
 * Неаутентифицированный доступ — «для личного/оценочного использования, ограниченное
 * число запросов в час»; для реального объёма вводится API-ключ (Bearer) — отдельным
 * срезом. Условия использования фиксируются в протоколе Этапа 0 (A24).</p>
 *
 * <p>Запрос: {@code GET <base>/v1/issuances?domain=<domain>&include_subdomains=true&expand=dns_names}.
 * Ответ — JSON-массив выпусков, у каждого {@code id} и {@code dns_names[]}. Пагинация:
 * {@code after=<id последнего выпуска>}, пока не вернётся пустой массив. Число страниц
 * ограничено бюджетом {@code app.discovery.ct.max-pages} (защита от неограниченного
 * обхода и уважение к лимитам источника). Запрос идёт через единый
 * {@link SourceHttpClient} — та же точка egress и SSRF-контур, что и у сбора (A13).</p>
 *
 * <p>Возвращаются <b>конкретные</b> имена хостов строго под доменом (сам домен-апекс
 * и wildcard-записи {@code *.foo} исключаются — они не дают тенанта). Отображение
 * хоста в кандидата-работодателя (для Workday — в {@code tenant/site}) — задача
 * вызывающей стороны (следующий срез).</p>
 */
@Component
public class CertSpotterClient {

    private final SourceHttpClient httpClient;
    private final String baseUrl;
    private final int maxPages;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param httpClient единый HTTP-клиент (SSRF-контур §9)
     * @param baseUrl    база Cert Spotter API (по умолчанию {@code https://api.certspotter.com})
     * @param maxPages   потолок числа страниц пагинации за один вызов (бюджет обхода)
     */
    public CertSpotterClient(
            SourceHttpClient httpClient,
            @Value("${app.discovery.ct.base-url:https://api.certspotter.com}") String baseUrl,
            @Value("${app.discovery.ct.max-pages:20}") int maxPages) {
        this.httpClient = httpClient;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.maxPages = maxPages;
    }

    /**
     * Перечисляет конкретные поддомены заданного домена по CT-логам.
     *
     * @param domain зарегистрированный домен вендора (напр. {@code myworkdayjobs.com})
     * @return различные имена хостов строго под доменом (без апекса и wildcard), в порядке обнаружения
     */
    public List<String> subdomainsOf(String domain) {
        String normalizedDomain = domain.toLowerCase().strip();
        Set<String> hosts = new LinkedHashSet<>();
        String after = null;

        for (int page = 0; page < maxPages; page++) {
            JsonNode issuances = parse(httpClient.getJson(buildUrl(normalizedDomain, after)));
            if (!issuances.isArray() || issuances.isEmpty()) {
                break;
            }
            String lastId = null;
            for (JsonNode issuance : issuances) {
                String id = textOrNull(issuance.path("id"));
                if (id != null) {
                    lastId = id;
                }
                for (JsonNode dnsName : issuance.path("dns_names")) {
                    collectHost(hosts, dnsName.asText(), normalizedDomain);
                }
            }
            if (lastId == null) {
                break; // без курсора следующую страницу не запросить — останавливаемся
            }
            after = lastId;
        }
        return new ArrayList<>(hosts);
    }

    /**
     * Добавляет конкретный хост под доменом, отбрасывая апекс и wildcard-записи.
     */
    private void collectHost(Set<String> hosts, String rawHost, String domain) {
        if (rawHost == null) {
            return;
        }
        String host = rawHost.toLowerCase().strip();
        if (host.startsWith("*.")) {
            return; // wildcard — не конкретный хост, тенанта не даёт
        }
        if (host.equals(domain)) {
            return; // апекс — не тенант
        }
        if (host.endsWith("." + domain)) {
            hosts.add(host);
        }
    }

    private String buildUrl(String domain, String after) {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/v1/issuances?domain=").append(enc(domain))
                .append("&include_subdomains=true&expand=dns_names");
        if (after != null && !after.isBlank()) {
            url.append("&after=").append(enc(after));
        }
        return url.toString();
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать ответ Cert Spotter", e);
        }
    }

    private String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
