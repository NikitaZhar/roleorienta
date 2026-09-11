package com.roleorienta.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Источник сбора: доска конкретной компании либо область (регион/специализация) на площадке.
 *
 * <p>Ссылается на {@link Provider}. Принадлежность одной компании необязательна —
 * связь с работодателями выражается через {@link CompanySource}. Пара
 * {@code (provider, externalRef)} уникальна. См. технический документ, §4–§5, ADR-16.</p>
 */
@Entity
@Table(
    name = "source",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_source_provider_external_ref",
        columnNames = {"provider_id", "external_ref"}
    )
)
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private Provider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceKind kind;

    /** Идентификатор доски/области в терминах провайдера (напр. slug доски). */
    @Column(name = "external_ref", nullable = false)
    private String externalRef;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    /** Расписание сбора (напр. cron-выражение), если задано. */
    @Column
    private String schedule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceState state;

    /** Версия адаптера, обслуживающего источник, если зафиксирована. */
    @Column(name = "adapter_version")
    private String adapterVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public SourceKind getKind() { return kind; }
    public void setKind(SourceKind kind) { this.kind = kind; }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    public SourceState getState() { return state; }
    public void setState(SourceState state) { this.state = state; }

    public String getAdapterVersion() { return adapterVersion; }
    public void setAdapterVersion(String adapterVersion) { this.adapterVersion = adapterVersion; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
