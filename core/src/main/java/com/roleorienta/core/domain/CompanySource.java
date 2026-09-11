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
 * Проверенная связь «источник → работодатель».
 *
 * <p>Для площадки связь многие-ко-многим: одна доска отдаёт вакансии многих
 * компаний. Пара {@code (source, company)} уникальна. См. технический документ,
 * §4, ADR-16.</p>
 */
@Entity
@Table(
    name = "company_source",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_company_source_source_company",
        columnNames = {"source_id", "company_id"}
    )
)
public class CompanySource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "verified_by", nullable = false)
    private VerifiedBy verifiedBy;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

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

    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }

    public VerifiedBy getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(VerifiedBy verifiedBy) { this.verifiedBy = verifiedBy; }

    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
