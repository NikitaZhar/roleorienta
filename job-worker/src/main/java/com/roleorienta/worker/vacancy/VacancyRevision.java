package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.crawl.CrawlRun;
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

/**
 * Запись истории вакансии (технический документ §4): значимое поле публикации «было → стало» и
 * полный обход, который это увидел; время — конец обхода. Создаётся только публикацией
 * ({@link JobPosting#update}) и сохраняется вместе с ней.
 */
@Entity
@Table(name = "vacancy_revision")
public class VacancyRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vacancy_id")
    private Vacancy vacancy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id")
    private JobPosting jobPosting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crawl_run_id")
    private CrawlRun crawlRun;

    @Enumerated(EnumType.STRING)
    private RevisionField field;

    private String oldValue;

    private String newValue;

    /**
     * Для JPA.
     */
    protected VacancyRevision() {
    }

    /**
     * @param jobPosting публикация-источник изменения; вакансия берётся из неё
     * @param crawlRun   полный обход, увидевший изменение; уже сохранён
     * @param field      изменённое поле
     * @param oldValue   было; {@code null} — не указано
     * @param newValue   стало; {@code null} — не указано
     */
    VacancyRevision(JobPosting jobPosting, CrawlRun crawlRun, RevisionField field, String oldValue,
            String newValue) {
        this.vacancy = jobPosting.getVacancy();
        this.jobPosting = jobPosting;
        this.crawlRun = crawlRun;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }
}
