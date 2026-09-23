package com.roleorienta.worker.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.worker.outbox.OutboxEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Сборка события outbox для задания {@code DISCOVER_EMPLOYER} — общая для всех входов
 * обнаружения (seed-гарвест §36, Common Crawl §55). Формат тела — тот, что разбирает
 * {@link DiscoverEmployerJobHandler}: {@code providerCode}, {@code slug}, {@code baseUrl}, {@code type}.
 */
public final class DiscoverEmployerPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DiscoverEmployerPayload() {
    }

    /**
     * @param providerCode код системы найма
     * @param slug         идентификатор доски у провайдера
     * @param baseUrl      базовый адрес ленты
     * @return событие outbox (агрегат {@code EmployerCandidate}, id агрегата — slug)
     */
    public static OutboxEvent event(String providerCode, String slug, String baseUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerCode", providerCode);
        body.put("slug", slug);
        body.put("baseUrl", baseUrl);
        body.put("type", CrawlTaskType.DISCOVER_EMPLOYER.name());
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сформировать payload DISCOVER_EMPLOYER", e);
        }
        return new OutboxEvent("EmployerCandidate", slug, CrawlTaskType.DISCOVER_EMPLOYER.name(), json, null);
    }
}
