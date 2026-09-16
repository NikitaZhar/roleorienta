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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Публикация вакансии в конкретном источнике.
 *
 * <p>Уникальна в паре {@code (source, externalId)} — внешние ID разных источников
 * не считаются глобально уникальными (§4, инвариант уникальности публикации).
 * Объединение публикаций в {@code Vacancy} и оценка покрытия вводятся следующим
 * срезом модели.</p>
 */
@Entity
@Table(
    name = "job_posting",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_job_posting_source_external_id",
        columnNames = {"source_id", "external_id"}
    )
)
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    /** Идентификатор публикации в терминах источника. */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String url;

    /** Заголовок публикации в исходном виде (нормализация — в следующих срезах). */
    @Column(name = "raw_title", nullable = false)
    private String rawTitle;

    /** Когда публикация впервые обнаружена приложением. */
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    /** Когда публикация в последний раз наблюдалась в источнике. */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Сырая локация с детальной страницы (нормализация — следующий срез, §6). */
    @Column(name = "raw_location")
    private String rawLocation;

    /** Сырая строка зарплаты/компенсации с детальной страницы (нормализация — следующий срез, §6, A09). */
    @Column(name = "raw_compensation")
    private String rawCompensation;

    /** Когда деталь публикации была дозапрошена заданием {@code FETCH_POSTING}, либо {@code null}. */
    @Column(name = "detail_fetched_at")
    private Instant detailFetchedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getRawTitle() { return rawTitle; }
    public void setRawTitle(String rawTitle) { this.rawTitle = rawTitle; }

    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public String getRawLocation() { return rawLocation; }
    public void setRawLocation(String rawLocation) { this.rawLocation = rawLocation; }

    public String getRawCompensation() { return rawCompensation; }
    public void setRawCompensation(String rawCompensation) { this.rawCompensation = rawCompensation; }

    public Instant getDetailFetchedAt() { return detailFetchedAt; }
    public void setDetailFetchedAt(Instant detailFetchedAt) { this.detailFetchedAt = detailFetchedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
