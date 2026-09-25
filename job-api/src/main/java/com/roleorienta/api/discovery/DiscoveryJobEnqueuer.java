package com.roleorienta.api.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlTaskType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Постановка задания {@code DISCOVER_EMPLOYER} в transactional outbox из job-api.
 *
 * <p>Обнаружение выполняет job-worker (собственный бюджет, SSRF-контур §32), поэтому
 * админ-триггер не ходит наружу сам, а пишет событие в {@code outbox_event} — ту же
 * таблицу, что и планировщик. Публикатор в job-worker подхватит строку, отправит в
 * очередь (messageId = id строки), а {@code DiscoverEmployerJobHandler} обработает.
 * Так producer (API) отделён от брокера тем же паттерном outbox (ADR-1).</p>
 *
 * <p>Вставка идёт напрямую SQL: у job-api нет своей JPA-сущности outbox (она —
 * концерн worker), а дублировать её ради одной вставки незачем. Поля {@code payload}
 * приводятся к {@code jsonb} явным кастом.</p>
 */
@Component
public class DiscoveryJobEnqueuer {

    private static final String INSERT_OUTBOX = """
            INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload, occurred_at, attempts)
            VALUES (?, ?, ?, ?::jsonb, now(), 0)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param jdbcTemplate доступ к БД (общая схема; таблицу ведёт Flyway job-api)
     */
    public DiscoveryJobEnqueuer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Ставит задание обнаружения одного кандидата.
     *
     * @param providerCode код системы найма (напр. {@code greenhouse})
     * @param slug         идентификатор доски у провайдера
     * @param baseUrl      базовый адрес ленты
     */
    public void enqueueDiscoverEmployer(String providerCode, String slug, String baseUrl) {
        jdbcTemplate.update(
                INSERT_OUTBOX,
                "EmployerCandidate",
                slug,
                CrawlTaskType.DISCOVER_EMPLOYER.name(),
                payload(providerCode, slug, baseUrl));
    }

    private String payload(String providerCode, String slug, String baseUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerCode", providerCode);
        body.put("slug", slug);
        body.put("baseUrl", baseUrl);
        body.put("type", CrawlTaskType.DISCOVER_EMPLOYER.name());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сформировать payload DISCOVER_EMPLOYER", exception);
        }
    }
}
