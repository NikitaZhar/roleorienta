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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Оценка покрытия публикации площадками (A5, ADR-15): результат сравнения с набором проверенных
 * площадок на дату, а не флаг «есть/нет». Одна запись на публикацию; нет записи — состояние
 * {@link CoverageState#UNKNOWN}.
 *
 * <p>Скелет (§81): таблица и чтение в ленте/карточке. Записи создаёт сравнение с площадкой —
 * следующий срез, после выбора эталонной площадки.</p>
 */
@Entity
@Table(name = "coverage_assessment")
public class CoverageAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false, unique = true)
    private JobPosting posting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CoverageState state;

    /** Проверенные площадки через «, » (область сравнения на момент проверки). */
    @Column(name = "checked_platforms")
    private String checkedPlatforms;

    /** Видимая причина состояния (для {@code UNKNOWN} — почему не проверено). */
    @Column
    private String reason;

    /** Когда выполнено сравнение; {@code null} — ещё не выполнялось. */
    @Column(name = "checked_at")
    private Instant checkedAt;

    /** Версия правил сопоставления, давших результат. */
    @Column(name = "matcher_version")
    private String matcherVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }

    public JobPosting getPosting() { return posting; }
    public void setPosting(JobPosting posting) { this.posting = posting; }

    public CoverageState getState() { return state; }
    public void setState(CoverageState state) { this.state = state; }

    public String getCheckedPlatforms() { return checkedPlatforms; }
    public void setCheckedPlatforms(String checkedPlatforms) { this.checkedPlatforms = checkedPlatforms; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }

    public String getMatcherVersion() { return matcherVersion; }
    public void setMatcherVersion(String matcherVersion) { this.matcherVersion = matcherVersion; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
