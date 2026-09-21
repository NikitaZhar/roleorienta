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
 * Внутреннее уведомление пользователя об изменении в вакансии компании, на которую он
 * подписан (§7, §8). Создаётся сопоставлением {@code MATCH_SUBSCRIPTIONS} (§39) из
 * записей {@link PendingChange} и подписок на компании (§37).
 *
 * <p>Владелец хранится простым {@code appUserId} (Long), а не связью на сущность
 * пользователя: {@code AppUser} живёт в job-api и недоступен в общем {@code core},
 * а уведомления создаёт job-worker. Оба приложения работают с этой общей сущностью:
 * worker пишет, job-api читает по {@code appUserId} владельца (A23). Внешние ключи на
 * {@code app_user}/{@code job_posting}/{@code company} задаются на уровне схемы (V21).</p>
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Владелец уведомления (id пользователя из подписки). */
    @Column(name = "app_user_id", nullable = false)
    private Long appUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    /** Компания, по подписке на которую сформировано уведомление. */
    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** Имя изменившегося поля (из {@link PendingChange}). */
    @Column(name = "field_name", nullable = false)
    private String fieldName;

    /** Момент прочтения пользователем или {@code null} — не прочитано. */
    @Column(name = "read_at")
    private Instant readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
    }

    /**
     * @param appUserId  владелец (id пользователя-подписчика)
     * @param jobPosting публикация, в которой произошло изменение
     * @param companyId  компания подписки
     * @param fieldName  имя изменившегося поля
     */
    public Notification(Long appUserId, JobPosting jobPosting, Long companyId, String fieldName) {
        this.appUserId = appUserId;
        this.jobPosting = jobPosting;
        this.companyId = companyId;
        this.fieldName = fieldName;
    }

    public Long getId() { return id; }

    public Long getAppUserId() { return appUserId; }

    public JobPosting getJobPosting() { return jobPosting; }

    public Long getCompanyId() { return companyId; }

    public String getFieldName() { return fieldName; }

    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }

    public Instant getCreatedAt() { return createdAt; }
}
