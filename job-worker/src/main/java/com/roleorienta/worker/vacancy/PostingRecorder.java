package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.source.Source;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Записывает результат чтения источника и пересчитывает состояние затронутых вакансий
 * (бизнес-описание §4.3).
 *
 * <ul>
 *   <li>Полное чтение: новая публикация — новая вакансия; известная — обновление сведений и
 *       подтверждение; отсутствующая — счётчик отсутствия, закрытие после
 *       {@link VacancyProperties#closeAfterMissingReads()} полных чтений подряд.</li>
 *   <li>Неполное чтение: добавляются только новые публикации; сохранённые не меняются и не
 *       закрываются.</li>
 *   <li>Отказ источника: сведения не меняются, подтверждения нет — вакансии источника нуждаются в
 *       повторной проверке.</li>
 * </ul>
 */
@Service
public class PostingRecorder {

    private final JobPostingRepository postings;
    private final VacancyRepository vacancies;
    private final VacancyProperties properties;
    private final Clock clock;

    /**
     * @param postings   доступ к публикациям
     * @param vacancies  доступ к вакансиям
     * @param properties настройки жизненного цикла
     * @param clock      часы
     */
    public PostingRecorder(JobPostingRepository postings, VacancyRepository vacancies,
            VacancyProperties properties, Clock clock) {
        this.postings = postings;
        this.vacancies = vacancies;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Записывает публикации одного чтения источника в одной транзакции.
     *
     * @param source   прочитанный источник
     * @param fetched  публикации из источника
     * @param complete список прочитан полностью
     */
    @Transactional
    public void record(Source source, List<FetchedPosting> fetched, boolean complete) {
        Instant now = clock.instant();
        Map<String, JobPosting> known = postings.findBySourceId(source.getId()).stream()
                .collect(Collectors.toMap(JobPosting::getExternalId, Function.identity()));
        Set<Long> touched = new HashSet<>();
        for (FetchedPosting posting : fetched) {
            JobPosting existing = known.remove(posting.externalId());
            if (existing == null) {
                Vacancy vacancy = vacancies.save(new Vacancy(posting.title(), posting.url(), now));
                postings.save(new JobPosting(source, vacancy, posting, now));
            } else if (complete) {
                existing.update(posting, now);
                existing.getVacancy().confirm(posting.title(), posting.url(), now);
                touched.add(existing.getVacancy().getId());
            }
        }
        if (complete) {
            for (JobPosting missing : known.values()) {
                missing.markMissing(properties.closeAfterMissingReads(), now);
                touched.add(missing.getVacancy().getId());
            }
        }
        refreshStates(touched, now);
    }

    /**
     * Источник не прочитан: публикации источника теряют подтверждение, счётчики и сведения не
     * меняются.
     *
     * @param source источник
     */
    @Transactional
    public void recordUnavailable(Source source) {
        Set<Long> touched = new HashSet<>();
        for (JobPosting posting : postings.findBySourceId(source.getId())) {
            posting.markUnconfirmed();
            touched.add(posting.getVacancy().getId());
        }
        refreshStates(touched, clock.instant());
    }

    private void refreshStates(Set<Long> vacancyIds, Instant now) {
        if (vacancyIds.isEmpty()) {
            return;
        }
        postings.findByVacancyIdIn(vacancyIds).stream()
                .collect(Collectors.groupingBy(JobPosting::getVacancy))
                .forEach((vacancy, vacancyPostings) -> vacancy.refreshState(vacancyPostings, now));
    }
}
