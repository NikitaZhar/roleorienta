package com.roleorienta.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Компания-работодатель.
 *
 * <p>Карьерные источники связываются с компанией не напрямую, а через
 * {@link CompanySource} (одна площадка обслуживает многих работодателей).
 * См. технический документ, §4, ADR-16.</p>
 */
@Entity
@Table(name = "company")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Основной домен работодателя, если известен (может меняться со временем). */
    @Column(name = "primary_domain")
    private String primaryDomain;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPrimaryDomain() { return primaryDomain; }
    public void setPrimaryDomain(String primaryDomain) { this.primaryDomain = primaryDomain; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
