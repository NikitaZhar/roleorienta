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
 * Языковое требование вакансии (§6, A07): один язык и то, что о нём сказано.
 *
 * <p>По правилу A07 факт упоминания ({@link #mentioned}) и обязательность
 * ({@link #modality}) хранятся раздельно, рядом — подтверждающий фрагмент текста и
 * версия правил извлечения (для воспроизводимости и повторной обработки). На одну
 * публикацию — не более одной строки на язык (уникальность {@code (job_posting,
 * language_code)}). Отсутствие строки для языка означает «не упомянут» — это не то
 * же самое, что {@code mentioned = NO} (явное «не требуется») или {@code UNKNOWN}
 * (не удалось разобрать).</p>
 *
 * <p>Значения полей заполняются через сеттеры (как у {@link JobPosting}), а не
 * конструктором со многими параметрами.</p>
 */
@Entity
@Table(
    name = "posting_language",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_posting_language_posting_code",
        columnNames = {"job_posting_id", "language_code"}
    )
)
public class PostingLanguage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    /** Код языка (ISO 639-1, напр. {@code en}, {@code de}). */
    @Column(name = "language_code", nullable = false)
    private String languageCode;

    /** Факт упоминания языка (независим от модальности). */
    @Enumerated(EnumType.STRING)
    @Column(name = "mentioned", nullable = false)
    private LanguageMention mentioned;

    /** Обязательность (независима от факта упоминания). */
    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false)
    private LanguageModality modality;

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

    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }

    public LanguageMention getMentioned() { return mentioned; }
    public void setMentioned(LanguageMention mentioned) { this.mentioned = mentioned; }

    public LanguageModality getModality() { return modality; }
    public void setModality(LanguageModality modality) { this.modality = modality; }

    public String getSourceFragment() { return sourceFragment; }
    public void setSourceFragment(String sourceFragment) { this.sourceFragment = sourceFragment; }

    public String getExtractionVersion() { return extractionVersion; }
    public void setExtractionVersion(String extractionVersion) { this.extractionVersion = extractionVersion; }

    public Instant getCreatedAt() { return createdAt; }
}
