package com.roleorienta.api.report;

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
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Жалоба пользователя на публикацию (§46) — «сообщить об ошибке».
 *
 * <p>Одна строка на пару «автор × публикация» (уникальный ключ
 * {@code (app_user_id, job_posting_id)}): повторная жалоба того же автора не создаёт дубля
 * (идемпотентность). Приватность (A23): автор — только из сессии, чужие жалобы недоступны.
 * Значения — через сеттеры (§3.10).</p>
 */
@Entity
@Table(name = "posting_report",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_posting_report_user_posting",
                columnNames = {"app_user_id", "job_posting_id"}))
public class PostingReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Автор жалобы. Определяется по сессии, никогда не приходит от клиента (§3.9, A23). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser reporter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting posting;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private PostingReportReason reason;

    @Column(name = "comment")
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PostingReportStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }

    public AppUser getReporter() { return reporter; }
    public void setReporter(AppUser reporter) { this.reporter = reporter; }

    public JobPosting getPosting() { return posting; }
    public void setPosting(JobPosting posting) { this.posting = posting; }

    public PostingReportReason getReason() { return reason; }
    public void setReason(PostingReportReason reason) { this.reason = reason; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public PostingReportStatus getStatus() { return status; }
    public void setStatus(PostingReportStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
