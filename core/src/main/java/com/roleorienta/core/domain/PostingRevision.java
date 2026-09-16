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
 * Одно зафиксированное изменение поля публикации (§6, «история изменений с первого
 * сбора»; A06/A16).
 *
 * <p>Каждая существенная смена нормализованного/сырого поля между сборами пишется
 * отдельной строкой «было → стало» с именем поля и моментом обнаружения. Первичное
 * заполнение (значение появилось впервые) изменением не считается и не пишется.
 * В карточке вакансии по этим строкам показывается история.</p>
 */
@Entity
@Table(name = "posting_revision")
public class PostingRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    /** Имя изменившегося поля (напр. {@code salary_min}). */
    @Column(name = "field_name", nullable = false)
    private String fieldName;

    /** Прежнее значение (строкой), либо {@code null}. */
    @Column(name = "old_value")
    private String oldValue;

    /** Новое значение (строкой), либо {@code null}. */
    @Column(name = "new_value")
    private String newValue;

    /** Момент, когда изменение обнаружено. */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PostingRevision() {
    }

    /**
     * @param jobPosting публикация, к которой относится изменение
     * @param fieldName  имя изменившегося поля
     * @param oldValue   прежнее значение
     * @param newValue   новое значение
     * @param detectedAt момент обнаружения
     */
    public PostingRevision(JobPosting jobPosting, String fieldName, String oldValue,
                           String newValue, Instant detectedAt) {
        this.jobPosting = jobPosting;
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.detectedAt = detectedAt;
    }

    public Long getId() { return id; }

    public JobPosting getJobPosting() { return jobPosting; }

    public String getFieldName() { return fieldName; }

    public String getOldValue() { return oldValue; }

    public String getNewValue() { return newValue; }

    public Instant getDetectedAt() { return detectedAt; }

    public Instant getCreatedAt() { return createdAt; }
}
