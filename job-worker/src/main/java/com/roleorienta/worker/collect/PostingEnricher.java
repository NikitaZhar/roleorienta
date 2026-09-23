package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.SeniorityLevel;
import com.roleorienta.core.domain.WorkModality;
import com.roleorienta.worker.adapters.FetchedPosting;
import com.roleorienta.worker.extract.ExperienceExtractor;
import com.roleorienta.worker.extract.ExtractedExperience;
import com.roleorienta.worker.normalize.LocationNormalizer;
import com.roleorienta.worker.normalize.NormalizedLocation;
import com.roleorienta.worker.normalize.NormalizedSalary;
import com.roleorienta.worker.normalize.SalaryNormalizer;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Обогащение публикации детальными данными: нормализация полей (зарплата, локация,
 * уровень опыта), запись структурированных требований (языки §6/A07, навыки §6/A08) и
 * фиксация истории изменений.
 *
 * <p>Обработчик {@code FetchPostingJobHandler} занимается только оркестрацией задания, а
 * доменное обогащение живёт здесь. Извлечение и запись требований из текста описания
 * (языки, навыки — строки дочерних таблиц) вынесены в {@link PostingRequirementWriter};
 * скалярные поля публикации (зарплата, локация, опыт) нормализуются/извлекаются прямо
 * здесь и пишутся в сущность. Метод {@link #enrich} меняет переданную сущность
 * {@link JobPosting} (её сохранение — за вызывающим, в той же транзакции) и переписывает
 * требования публикации.</p>
 */
@Component
public class PostingEnricher {

    private static final Logger log = LoggerFactory.getLogger(PostingEnricher.class);

    private final SalaryNormalizer salaryNormalizer;
    private final LocationNormalizer locationNormalizer;
    private final ExperienceExtractor experienceExtractor;
    private final PostingRevisionRecorder revisionRecorder;
    private final PostingRequirementWriter requirementWriter;

    /**
     * @param salaryNormalizer    нормализатор зарплаты
     * @param locationNormalizer  нормализатор локации
     * @param experienceExtractor извлечение уровня опыта
     * @param revisionRecorder    запись истории изменений полей
     * @param requirementWriter   извлечение и запись требований (языки, навыки)
     */
    public PostingEnricher(
            SalaryNormalizer salaryNormalizer,
            LocationNormalizer locationNormalizer,
            ExperienceExtractor experienceExtractor,
            PostingRevisionRecorder revisionRecorder,
            PostingRequirementWriter requirementWriter) {
        this.salaryNormalizer = salaryNormalizer;
        this.locationNormalizer = locationNormalizer;
        this.experienceExtractor = experienceExtractor;
        this.revisionRecorder = revisionRecorder;
        this.requirementWriter = requirementWriter;
    }

    /**
     * Применяет к публикации детальные данные: нормализует и записывает поля, фиксирует
     * реальные изменения в историю и переписывает требования (языки, навыки).
     *
     * @param posting публикация (управляемая сущность; сохранение — за вызывающим)
     * @param detail  детальные поля из адаптера
     * @param at      момент сбора (в историю и в {@code detail_fetched_at})
     */
    public void enrich(JobPosting posting, FetchedPosting detail, Instant at) {
        NormalizedSalary salary = salaryNormalizer.normalize(detail.compensation());
        NormalizedLocation location = locationNormalizer.normalize(
                detail.rawLocation(), detail.country(), detail.remoteType());
        ExtractedExperience experience = experienceExtractor.extract(detail.rawDescription());

        // Прежние значения — до перезаписи, чтобы зафиксировать реальные изменения (§6).
        recordChanges(posting, detail.rawLocation(), location, salary, experience, at);

        posting.setRawLocation(detail.rawLocation());
        posting.setCity(location.city());
        posting.setCountry(location.country());
        posting.setWorkModality(location.modality());
        posting.setRawCompensation(detail.rawCompensation());
        posting.setSalaryMin(salary.min());
        posting.setSalaryMax(salary.max());
        posting.setSalaryCurrency(salary.currency());
        posting.setSalaryPeriod(salary.period());
        posting.setSalaryBasis(salary.basis());
        posting.setSeniority(experience.level());
        posting.setExperienceYearsMin(experience.yearsMin());
        posting.setRawDescription(detail.rawDescription());
        if (detail.postedOn() != null) {
            posting.setPostedOn(detail.postedOn());   // источник не сообщил — прежнее значение не затираем
        }
        posting.setAdditionalLocations(detail.additionalLocations().isEmpty()
                ? null : String.join("; ", detail.additionalLocations()));
        posting.setDetailFetchedAt(at);

        PostingRequirementWriter.RequirementCounts counts =
                requirementWriter.write(posting, detail.rawDescription());

        log.info("Обогащение публикации {}: локация={} (город={}, страна={}, формат={}, доп. локаций={}), "
                        + "опубликовано={}, зарплата={} {}–{} (период={}, база={}), опыт={}/лет≥{}, языков={}, навыков={}",
                posting.getExternalId(), detail.rawLocation(), location.city(), location.country(),
                location.modality(), detail.additionalLocations().size(), detail.postedOn(),
                salary.currency(), salary.min(), salary.max(),
                salary.period(), salary.basis(), experience.level(), experience.yearsMin(),
                counts.languages(), counts.skills());
    }

    /**
     * Фиксирует изменения детальных полей относительно прежних значений публикации
     * (сравнение до перезаписи). Пишутся только реальные смены (см. {@link PostingRevisionRecorder}).
     */
    private void recordChanges(JobPosting posting, String newLocation, NormalizedLocation location,
                               NormalizedSalary salary, ExtractedExperience experience, Instant at) {
        revisionRecorder.recordIfChanged(posting, "raw_location",
                posting.getRawLocation(), newLocation, at);
        revisionRecorder.recordIfChanged(posting, "city",
                posting.getCity(), location.city(), at);
        revisionRecorder.recordIfChanged(posting, "country",
                posting.getCountry(), location.country(), at);
        revisionRecorder.recordIfChanged(posting, "work_modality",
                name(posting.getWorkModality()), name(location.modality()), at);
        revisionRecorder.recordIfChanged(posting, "salary_min",
                str(posting.getSalaryMin()), str(salary.min()), at);
        revisionRecorder.recordIfChanged(posting, "salary_max",
                str(posting.getSalaryMax()), str(salary.max()), at);
        revisionRecorder.recordIfChanged(posting, "salary_currency",
                posting.getSalaryCurrency(), salary.currency(), at);
        revisionRecorder.recordIfChanged(posting, "seniority",
                name(posting.getSeniority()), name(experience.level()), at);
        revisionRecorder.recordIfChanged(posting, "experience_years_min",
                str(posting.getExperienceYearsMin()), str(experience.yearsMin()), at);
    }

    /** Число в строку без хвостовых нулей, либо {@code null}. */
    private String str(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** Целое в строку, либо {@code null} (для сравнения числа лет в истории). */
    private String str(Integer value) {
        return value == null ? null : value.toString();
    }

    /** Имя перечисления, либо {@code null} (для сравнения формата работы в истории). */
    private String name(WorkModality modality) {
        return modality == null ? null : modality.name();
    }

    /** Имя перечисления, либо {@code null} (для сравнения уровня опыта в истории). */
    private String name(SeniorityLevel level) {
        return level == null ? null : level.name();
    }
}
