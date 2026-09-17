package com.roleorienta.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Учётная запись пользователя (§9): вход по email, роль и хэш пароля.
 *
 * <p>Хранится только bcrypt-хэш пароля (§3.9) — открытый пароль в системе не
 * задерживается. Уникальность email — без учёта регистра (индекс по
 * {@code lower(email)}, миграция V14). Значения заполняются через сеттеры, как у
 * других сущностей проекта (например {@code PostingLanguage}), а не конструктором
 * со многими параметрами (§3.10).</p>
 *
 * <p>Сущность живёт в модуле {@code job-api} (пакет {@code com.roleorienta.api.auth}),
 * а не в общем {@code core}: пользователями управляет только API, {@code job-worker}
 * их не использует. Пакет добавлен в {@code @EntityScan} приложения.</p>
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Логин пользователя (email). */
    @Column(name = "email", nullable = false)
    private String email;

    /** Bcrypt-хэш пароля в формате {@code {bcrypt}...} (не сам пароль). */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** Роль пользователя. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
