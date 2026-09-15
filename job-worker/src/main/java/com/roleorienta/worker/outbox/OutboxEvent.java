package com.roleorienta.worker.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Строка таблицы {@code outbox_event} (transactional outbox, миграция V2).
 *
 * <p>События записываются в эту таблицу в той же транзакции БД, что и изменение
 * данных (записывающую сторону добавит инкремент планировщика). Публикатор в
 * job-worker читает неопубликованные строки, отправляет их в RabbitMQ с
 * publisher confirms и проставляет {@code publishedAt}. Обзор паттерна:
 * https://microservices.io/patterns/data/transactional-outbox.html</p>
 *
 * <p>Поля {@code payload}/{@code headers} — тип {@code jsonb}. Маппинг
 * {@link JdbcTypeCode}{@code (SqlTypes.JSON)} на {@link String} хранит и читает
 * их как «сырой» JSON без промежуточной десериализации: публикатору нужно лишь
 * переслать тело события дальше в брокер.</p>
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", updatable = false, columnDefinition = "jsonb")
    private String headers;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected OutboxEvent() {
    }

    /**
     * Создаёт новое событие для записи в outbox. {@code occurredAt} ставится в
     * текущий момент, {@code attempts}=0, {@code publishedAt}=null (не опубликовано).
     *
     * @param aggregateType тип агрегата-источника события (например, {@code CrawlTask})
     * @param aggregateId   идентификатор агрегата
     * @param eventType     тип события (например, тип задания)
     * @param payload       тело события в виде JSON
     * @param headers       заголовки в виде JSON или {@code null}
     */
    public OutboxEvent(String aggregateType, String aggregateId, String eventType,
                       String payload, String headers) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.headers = headers;
        this.occurredAt = Instant.now();
        this.attempts = 0;
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getHeaders() {
        return headers;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
