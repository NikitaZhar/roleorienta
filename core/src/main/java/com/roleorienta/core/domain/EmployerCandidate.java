package com.roleorienta.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Найденный, но ещё не подтверждённый работодатель/источник (§4, §5, ADR-6).
 *
 * <p>Заводится слоем обнаружения по результату проверки ленты кандидата и живёт в
 * очереди подтверждения: администратор подтверждает (заводится {@link Source} и
 * ставится на сбор) или отклоняет. Пара {@code (providerCode, slug)} уникальна —
 * один и тот же кандидат не заводится дважды из повторных запусков (дедуп §5).</p>
 *
 * <p>Ссылки на созданные {@code Company}/{@code Source} хранятся простыми
 * идентификаторами (заполняются при подтверждении), чтобы не тянуть связи до
 * подтверждения. Значения задаются сеттерами, как у других сущностей проекта
 * (§3.10), а не конструктором со многими параметрами.</p>
 */
@Entity
@Table(
    name = "employer_candidate",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_employer_candidate_provider_slug",
        columnNames = {"provider_code", "slug"}
    )
)
public class EmployerCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Код системы найма кандидата (напр. {@code greenhouse}); совпадает с {@code Provider.code}. */
    @Column(name = "provider_code", nullable = false)
    private String providerCode;

    /** Идентификатор доски в терминах провайдера (slug). */
    @Column(name = "slug", nullable = false)
    private String slug;

    /** Базовый адрес ленты (из входа обнаружения); используется при заведении {@link Source}. */
    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private EmployerCandidateState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false)
    private DiscoveryConfidence confidence;

    /** Человекочитаемое обоснование результата проверки (для очереди подтверждения). */
    @Column(name = "reason")
    private String reason;

    /** Число публикаций, увиденных при проверке ленты (объём выборки для решения). */
    @Column(name = "posting_count", nullable = false)
    private int postingCount;

    /** Компания, созданная при подтверждении (иначе {@code null}). */
    @Column(name = "company_id")
    private Long companyId;

    /** Источник, созданный при подтверждении (иначе {@code null}). */
    @Column(name = "source_id")
    private Long sourceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public EmployerCandidateState getState() { return state; }
    public void setState(EmployerCandidateState state) { this.state = state; }

    public DiscoveryConfidence getConfidence() { return confidence; }
    public void setConfidence(DiscoveryConfidence confidence) { this.confidence = confidence; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public int getPostingCount() { return postingCount; }
    public void setPostingCount(int postingCount) { this.postingCount = postingCount; }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
