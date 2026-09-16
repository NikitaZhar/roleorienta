package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.PostingLanguage;
import com.roleorienta.core.domain.WorkModality;
import com.roleorienta.worker.adapters.FetchedPosting;
import com.roleorienta.worker.extract.ExtractedLanguage;
import com.roleorienta.worker.extract.LanguageExtractor;
import com.roleorienta.worker.normalize.LocationNormalizer;
import com.roleorienta.worker.normalize.NormalizedLocation;
import com.roleorienta.worker.normalize.NormalizedSalary;
import com.roleorienta.worker.normalize.SalaryNormalizer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Обогащение публикации детальными данными: нормализация полей (зарплата, локация),
 * извлечение языков (§6, A07) и фиксация истории изменений.
 *
 * <p>Вынесено из {@code FetchPostingJobHandler}, чтобы обработчик занимался только
 * оркестрацией задания (найти источник/публикацию, вызвать адаптер, отметить задание),
 * а доменное обогащение жило в одном месте и не раздувало конструктор обработчика.
 * Метод {@link #enrich} меняет переданную сущность {@link JobPosting} (её сохранение —
 * за вызывающим, в той же транзакции) и переписывает языки публикации.</p>
 */
@Component
public class PostingEnricher {

    private static final Logger log = LoggerFactory.getLogger(PostingEnricher.class);

    private final SalaryNormalizer salaryNormalizer;
    private final LocationNormalizer locationNormalizer;
    private final LanguageExtractor languageExtractor;
    private final PostingRevisionRecorder revisionRecorder;
    private final PostingLanguageRepository postingLanguageRepository;

    /**
     * @param salaryNormalizer          нормализатор зарплаты
     * @param locationNormalizer        нормализатор локации
     * @param languageExtractor         извлечение языков
     * @param revisionRecorder          запись истории изменений полей
     * @param postingLanguageRepository языковые требования публикации
     */
    public PostingEnricher(
            SalaryNormalizer salaryNormalizer,
            LocationNormalizer locationNormalizer,
            LanguageExtractor languageExtractor,
            PostingRevisionRecorder revisionRecorder,
            PostingLanguageRepository postingLanguageRepository) {
        this.salaryNormalizer = salaryNormalizer;
        this.locationNormalizer = locationNormalizer;
        this.languageExtractor = languageExtractor;
        this.revisionRecorder = revisionRecorder;
        this.postingLanguageRepository = postingLanguageRepository;
    }

    /**
     * Применяет к публикации детальные данные: нормализует и записывает поля, фиксирует
     * реальные изменения в историю и переписывает языковые требования.
     *
     * @param posting публикация (управляемая сущность; сохранение — за вызывающим)
     * @param detail  детальные поля из адаптера
     * @param at      момент сбора (в историю и в {@code detail_fetched_at})
     */
    public void enrich(JobPosting posting, FetchedPosting detail, Instant at) {
        NormalizedSalary salary = salaryNormalizer.normalize(detail.compensation());
        NormalizedLocation location = locationNormalizer.normalize(detail.rawLocation());
        List<ExtractedLanguage> languages = languageExtractor.extract(detail.rawDescription());

        // Прежние значения — до перезаписи, чтобы зафиксировать реальные изменения (§6).
        recordChanges(posting, detail.rawLocation(), location, salary, at);

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
        posting.setRawDescription(detail.rawDescription());
        posting.setDetailFetchedAt(at);

        replaceLanguages(posting, languages);

        log.info("Обогащение публикации {}: локация={} (город={}, страна={}, формат={}), "
                        + "зарплата={} {}–{} (период={}, база={}), языков={}",
                posting.getExternalId(), detail.rawLocation(), location.city(), location.country(),
                location.modality(), salary.currency(), salary.min(), salary.max(),
                salary.period(), salary.basis(), languages.size());
    }

    /**
     * Переписывает языковые требования публикации: удаляет прежние и вставляет текущие.
     * Полная замена идемпотентна относительно содержимого описания; ревизии по языкам в
     * этот срез не вводятся.
     */
    private void replaceLanguages(JobPosting posting, List<ExtractedLanguage> languages) {
        postingLanguageRepository.deleteByJobPosting_Id(posting.getId());
        for (ExtractedLanguage language : languages) {
            PostingLanguage row = new PostingLanguage();
            row.setJobPosting(posting);
            row.setLanguageCode(language.languageCode());
            row.setMentioned(language.mentioned());
            row.setModality(language.modality());
            row.setSourceFragment(language.fragment());
            row.setExtractionVersion(LanguageExtractor.VERSION);
            postingLanguageRepository.save(row);
        }
    }

    /**
     * Фиксирует изменения детальных полей относительно прежних значений публикации
     * (сравнение до перезаписи). Пишутся только реальные смены (см. {@link PostingRevisionRecorder}).
     */
    private void recordChanges(JobPosting posting, String newLocation, NormalizedLocation location,
                               NormalizedSalary salary, Instant at) {
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
    }

    /** Число в строку без хвостовых нулей, либо {@code null}. */
    private String str(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** Имя перечисления, либо {@code null} (для сравнения формата работы в истории). */
    private String name(WorkModality modality) {
        return modality == null ? null : modality.name();
    }
}
