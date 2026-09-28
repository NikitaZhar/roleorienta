package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.source.Source;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Записывает результат чтения источника: новая публикация — новая вакансия; известная —
 * обновление сведений и подтверждение наличия. Одна публикация — одна запись (уникальность
 * «источник + внешний id»).
 */
@Service
public class PostingRecorder {

    private final JobPostingRepository postings;
    private final VacancyRepository vacancies;
    private final Clock clock;

    /**
     * @param postings  доступ к публикациям
     * @param vacancies доступ к вакансиям
     * @param clock     часы
     */
    public PostingRecorder(JobPostingRepository postings, VacancyRepository vacancies, Clock clock) {
        this.postings = postings;
        this.vacancies = vacancies;
        this.clock = clock;
    }

    /**
     * Записывает публикации одного чтения источника в одной транзакции.
     *
     * @param source  прочитанный источник
     * @param fetched публикации из источника
     */
    @Transactional
    public void record(Source source, List<FetchedPosting> fetched) {
        Instant now = clock.instant();
        for (FetchedPosting posting : fetched) {
            postings.findBySourceIdAndExternalId(source.getId(), posting.externalId())
                    .ifPresentOrElse(known -> {
                        known.update(posting, now);
                        known.getVacancy().confirm(posting.title(), posting.url(), now);
                    }, () -> {
                        Vacancy vacancy = vacancies.save(new Vacancy(posting.title(), posting.url(), now));
                        postings.save(new JobPosting(source, vacancy, posting, now));
                    });
        }
    }
}
