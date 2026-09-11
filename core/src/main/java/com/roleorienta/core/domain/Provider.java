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
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Провайдер — тип интеграции (конкретная ATS или площадка) и его capability-карточка.
 *
 * <p>Описывает возможности источников данного типа (виды пагинации, языки,
 * обязательность credentials, полноту перечисления, семантику дат и т.п.),
 * хранимые гибко в поле {@code capabilities} (JSONB). См. технический документ,
 * §4 и §5, ADR-16.</p>
 */
@Entity
@Table(
    name = "provider",
    uniqueConstraints = @UniqueConstraint(name = "uq_provider_code", columnNames = "code")
)
public class Provider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Стабильный код провайдера, напр. {@code greenhouse}. Уникален. */
    @Column(nullable = false)
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderKind kind;

    /** Capability-карточка провайдера; произвольная структура, хранится как JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> capabilities;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public ProviderKind getKind() { return kind; }
    public void setKind(ProviderKind kind) { this.kind = kind; }

    public Map<String, Object> getCapabilities() { return capabilities; }
    public void setCapabilities(Map<String, Object> capabilities) { this.capabilities = capabilities; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
