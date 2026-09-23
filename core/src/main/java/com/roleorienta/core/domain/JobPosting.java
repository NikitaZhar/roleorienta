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
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Публикация вакансии в конкретном источнике.
 *
 * <p>Уникальна в паре {@code (source, externalId)} — внешние ID разных источников
 * не считаются глобально уникальными (§4, инвариант уникальности публикации).
 * Объединение публикаций в {@code Vacancy} и оценка покрытия вводятся следующим
 * срезом модели.</p>
 */
@Entity
@Table(
    name = "job_posting",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_job_posting_source_external_id",
        columnNames = {"source_id", "external_id"}
    )
)
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    /** Идентификатор публикации в терминах источника. */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String url;

    /** Заголовок публикации в исходном виде (нормализация — в следующих срезах). */
    @Column(name = "raw_title", nullable = false)
    private String rawTitle;

    /** Когда публикация впервые обнаружена приложением. */
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    /** Когда публикация в последний раз наблюдалась в источнике. */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Сырая локация с детальной страницы; нормализованные поля — рядом ниже (§6). */
    @Column(name = "raw_location")
    private String rawLocation;

    /** Нормализованный город из строки локации, либо {@code null}. */
    @Column(name = "city")
    private String city;

    /** Нормализованная страна/регион из строки локации, либо {@code null}. */
    @Column(name = "country")
    private String country;

    /** Формат работы; {@code UNKNOWN}, если есть локация без remote/hybrid; {@code null} — локации нет. */
    @Enumerated(EnumType.STRING)
    @Column(name = "work_modality")
    private WorkModality workModality;

    /** Текст описания вакансии (снят из HTML источника), либо {@code null}. Источник для извлечения (§6). */
    @Column(name = "raw_description")
    private String rawDescription;

    /** Сырая строка зарплаты/компенсации с детальной страницы (нормализация — следующий срез, §6, A09). */
    @Column(name = "raw_compensation")
    private String rawCompensation;

    /** Когда деталь публикации была дозапрошена заданием {@code FETCH_POSTING}, либо {@code null}. */
    @Column(name = "detail_fetched_at")
    private Instant detailFetchedAt;

    /** Нормализованная нижняя граница зарплаты, либо {@code null} (зарплата не указана). */
    @Column(name = "salary_min")
    private BigDecimal salaryMin;

    /** Нормализованная верхняя граница зарплаты, либо {@code null}. */
    @Column(name = "salary_max")
    private BigDecimal salaryMax;

    /** Код валюты зарплаты (напр. {@code EUR}), либо {@code null}. */
    @Column(name = "salary_currency")
    private String salaryCurrency;

    /** Период зарплаты; {@code UNKNOWN}, если есть сумма, но период не указан; {@code null} — зарплаты нет. */
    @Enumerated(EnumType.STRING)
    @Column(name = "salary_period")
    private SalaryPeriod salaryPeriod;

    /** База (gross/net); {@code UNKNOWN}, если есть сумма, но база не указана; {@code null} — зарплаты нет. */
    @Enumerated(EnumType.STRING)
    @Column(name = "salary_basis")
    private SalaryBasis salaryBasis;

    /** Уровень опыта (seniority) из описания; {@code UNKNOWN}, если из текста не следует. */
    @Enumerated(EnumType.STRING)
    @Column(name = "seniority")
    private SeniorityLevel seniority;

    /** Минимально требуемое число лет опыта из описания, либо {@code null} (не указано). */
    @Column(name = "experience_years_min")
    private Integer experienceYearsMin;

    /** Дата публикации по данным источника (Workday {@code startDate}), либо {@code null} (не сообщается, §65). */
    @Column(name = "posted_on")
    private java.time.LocalDate postedOn;

    /** Дополнительные локации многолокационной вакансии через «; », либо {@code null} (§65). */
    @Column(name = "additional_locations")
    private String additionalLocations;

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

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getRawTitle() { return rawTitle; }
    public void setRawTitle(String rawTitle) { this.rawTitle = rawTitle; }

    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public String getRawLocation() { return rawLocation; }
    public void setRawLocation(String rawLocation) { this.rawLocation = rawLocation; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public WorkModality getWorkModality() { return workModality; }
    public void setWorkModality(WorkModality workModality) { this.workModality = workModality; }

    public String getRawDescription() { return rawDescription; }
    public void setRawDescription(String rawDescription) { this.rawDescription = rawDescription; }

    public String getRawCompensation() { return rawCompensation; }
    public void setRawCompensation(String rawCompensation) { this.rawCompensation = rawCompensation; }

    public Instant getDetailFetchedAt() { return detailFetchedAt; }
    public void setDetailFetchedAt(Instant detailFetchedAt) { this.detailFetchedAt = detailFetchedAt; }

    public java.time.LocalDate getPostedOn() { return postedOn; }
    public void setPostedOn(java.time.LocalDate postedOn) { this.postedOn = postedOn; }

    public String getAdditionalLocations() { return additionalLocations; }
    public void setAdditionalLocations(String additionalLocations) { this.additionalLocations = additionalLocations; }

    public BigDecimal getSalaryMin() { return salaryMin; }
    public void setSalaryMin(BigDecimal salaryMin) { this.salaryMin = salaryMin; }

    public BigDecimal getSalaryMax() { return salaryMax; }
    public void setSalaryMax(BigDecimal salaryMax) { this.salaryMax = salaryMax; }

    public String getSalaryCurrency() { return salaryCurrency; }
    public void setSalaryCurrency(String salaryCurrency) { this.salaryCurrency = salaryCurrency; }

    public SalaryPeriod getSalaryPeriod() { return salaryPeriod; }
    public void setSalaryPeriod(SalaryPeriod salaryPeriod) { this.salaryPeriod = salaryPeriod; }

    public SalaryBasis getSalaryBasis() { return salaryBasis; }
    public void setSalaryBasis(SalaryBasis salaryBasis) { this.salaryBasis = salaryBasis; }

    public SeniorityLevel getSeniority() { return seniority; }
    public void setSeniority(SeniorityLevel seniority) { this.seniority = seniority; }

    public Integer getExperienceYearsMin() { return experienceYearsMin; }
    public void setExperienceYearsMin(Integer experienceYearsMin) { this.experienceYearsMin = experienceYearsMin; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
