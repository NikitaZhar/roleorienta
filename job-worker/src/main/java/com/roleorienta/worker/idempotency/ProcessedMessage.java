package com.roleorienta.worker.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Строка таблицы {@code processed_message} (миграция V4) — ключ идемпотентности
 * уже обработанного сообщения.
 *
 * <p>Используется потребителем, чтобы повторная доставка того же сообщения
 * (семантика at-least-once) не выполняла обработку дважды (ADR-3).</p>
 */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
