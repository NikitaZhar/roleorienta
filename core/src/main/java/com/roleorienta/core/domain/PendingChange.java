package com.roleorienta.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Устойчивый журнал изменений, ожидающих сопоставления с подписками (§8, A16).
 *
 * <p>Пишется в той же транзакции, что и {@link PostingRevision} — при каждом реальном
 * изменении поля публикации. Не зависит от брокера: даже если между записью ревизии и
 * рассылкой уведомлений что-то упадёт, изменение остаётся в журнале и будет обработано
 * позже. Сопоставление с подписками и формирование уведомлений
 * ({@code MATCH_SUBSCRIPTIONS}) проставит {@code processedAt} — до тех пор запись
 * считается необработанной (§39).</p>
 */
@Entity
@Table(name = "pending_change")
public class PendingChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    /** Имя изменившегося поля (то же, что в {@link PostingRevision}). */
    @Column(name = "field_name", nullable = false)
    private String fieldName;

    /** Момент обнаружения изменения. */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** Момент сопоставления с подписками или {@code null} — ещё не обработано (§39). */
    @Column(name = "processed_at")
    private Instant processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PendingChange() {
    }

    /**
     * @param jobPosting публикация, у которой изменилось поле
     * @param fieldName  имя изменившегося поля
     * @param detectedAt момент обнаружения
     */
    public PendingChange(JobPosting jobPosting, String fieldName, Instant detectedAt) {
        this.jobPosting = jobPosting;
        this.fieldName = fieldName;
        this.detectedAt = detectedAt;
    }

    public Long getId() { return id; }

    public JobPosting getJobPosting() { return jobPosting; }

    public String getFieldName() { return fieldName; }

    public Instant getDetectedAt() { return detectedAt; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }

    public Instant getCreatedAt() { return createdAt; }
}
