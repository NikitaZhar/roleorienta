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
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

/**
 * Структурированное требование-навык вакансии (§6, A08): один навык из таксономии и
 * то, что о нём сказано в описании.
 *
 * <p>Навык хранится в каноническом виде ({@link #skill}, напр. {@code PostgreSQL}) —
 * алиасы источника ({@code Postgres}/{@code PostgreSQL}) сводятся к одному навыку ещё
 * при извлечении, поэтому на одну публикацию — не более одной строки на навык
 * (уникальность {@code (job_posting, skill)}). Хранятся раздельно (A08): отношение к
 * навыку ({@link #stance} — запрос/отрицание/миграция) и обязательность
 * ({@link #modality}); рядом — подтверждающий фрагмент текста и версия правил извлечения
 * (для воспроизводимости и повторной обработки). Отсутствие строки означает «навык не
 * упомянут». Языки — отдельная сущность {@link PostingLanguage} со своей моделью A07;
 * связанные навыки (Java и JVM) и иерархия таксономии — предмет отдельного среза.</p>
 *
 * <p>Значения полей заполняются через сеттеры (как у {@link JobPosting} и
 * {@link PostingLanguage}), а не конструктором со многими параметрами.</p>
 */
@Entity
@Table(
    name = "posting_skill",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_posting_skill_posting_skill",
        columnNames = {"job_posting_id", "skill"}
    )
)
public class PostingSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    /** Каноническое имя навыка из таксономии (напр. {@code PostgreSQL}, {@code C#}). */
    @Column(name = "skill", nullable = false)
    private String skill;

    /** Отношение к навыку (запрос/отрицание/миграция); независимо от обязательности. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stance", nullable = false)
    private SkillStance stance;

    /** Обязательность требования (по формулировке; при неоднозначности — {@code UNSPECIFIED}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false)
    private RequirementModality modality;

    /** Фрагмент текста-подтверждение (предложение с упоминанием), либо {@code null}. */
    @Column(name = "source_fragment")
    private String sourceFragment;

    /** Версия правил извлечения, которыми получена строка. */
    @Column(name = "extraction_version", nullable = false)
    private String extractionVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }

    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }

    public String getSkill() { return skill; }
    public void setSkill(String skill) { this.skill = skill; }

    public SkillStance getStance() { return stance; }
    public void setStance(SkillStance stance) { this.stance = stance; }

    public RequirementModality getModality() { return modality; }
    public void setModality(RequirementModality modality) { this.modality = modality; }

    public String getSourceFragment() { return sourceFragment; }
    public void setSourceFragment(String sourceFragment) { this.sourceFragment = sourceFragment; }

    public String getExtractionVersion() { return extractionVersion; }
    public void setExtractionVersion(String extractionVersion) { this.extractionVersion = extractionVersion; }

    public Instant getCreatedAt() { return createdAt; }
}
