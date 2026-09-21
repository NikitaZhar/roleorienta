package com.roleorienta.api.application;

import com.roleorienta.api.auth.AppUser;
import com.roleorienta.core.domain.JobPosting;
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
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Отклик пользователя на публикацию (§7) — часть пути кандидата.
 *
 * <p>Одна строка на пару «пользователь × публикация» (уникальный ключ
 * {@code (app_user_id, job_posting_id)}, миграция V22): повторный отклик на ту же
 * публикацию не создаёт дубля (§7.8; идемпотентность создания). Приватность (A23):
 * владелец — только из сессии, данные одного пользователя недоступны другому.</p>
 */
@Entity
@Table(name = "application",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_application_user_posting",
                columnNames = {"app_user_id", "job_posting_id"}))
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Владелец отклика. Определяется по сессии, никогда не приходит от клиента (§3.9, A23). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser user;

    /** Публикация, на которую откликнулись. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting posting;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApplicationStatus status;

    /**
     * Версия оптимистичной блокировки (A19). Служит сильным ETag карточки: каждое
     * изменение увеличивает её, поэтому устаревший {@code If-Match} даёт 412, а
     * гонка одновременных изменений — 409.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public JobPosting getPosting() { return posting; }
    public void setPosting(JobPosting posting) { this.posting = posting; }

    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }

    public long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
