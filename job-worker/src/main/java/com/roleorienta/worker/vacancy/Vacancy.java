package com.roleorienta.worker.vacancy;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

/**
 * Вакансия — одна запись над одной или несколькими публикациями (бизнес-описание §4.3).
 * {@link Enumerated} с {@code STRING} хранит состояние именем, а не порядковым номером.
 */
@Entity
@Table(name = "vacancy")
public class Vacancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private VacancyState state;

    private String title;

    private String primaryUrl;

    private Instant firstSeenAt;

    private Instant lastConfirmedAt;

    private Instant closedAt;

    /**
     * Для JPA.
     */
    protected Vacancy() {
    }

    /**
     * Новая вакансия, подтверждённая источником в момент первого обнаружения.
     *
     * @param title      позиция
     * @param primaryUrl ссылка на первичную публикацию
     * @param seenAt     момент обнаружения
     */
    public Vacancy(String title, String primaryUrl, Instant seenAt) {
        this.state = VacancyState.ACTIVE;
        this.title = title;
        this.primaryUrl = primaryUrl;
        this.firstSeenAt = seenAt;
        this.lastConfirmedAt = seenAt;
    }

    /**
     * Источник подтвердил вакансию: сведения и дата подтверждения обновлены. Состояние задаёт
     * {@link #refreshState}.
     *
     * @param newTitle      позиция
     * @param newPrimaryUrl ссылка на первичную публикацию
     * @param confirmedAt   момент подтверждения
     */
    public void confirm(String newTitle, String newPrimaryUrl, Instant confirmedAt) {
        this.title = newTitle;
        this.primaryUrl = newPrimaryUrl;
        this.lastConfirmedAt = confirmedAt;
    }

    /**
     * Состояние по всем публикациям вакансии (бизнес-описание §4.3): все закрыты — закрыта; хотя бы
     * одна подтверждена последним чтением своего источника — актуальна; иначе — нуждается в
     * повторной проверке. Закрытие не удаляет вакансию и её историю.
     *
     * @param postings все публикации вакансии; не пустой список
     * @param now      момент пересчёта — дата закрытия, если вакансия закрывается сейчас
     */
    public void refreshState(List<JobPosting> postings, Instant now) {
        if (postings.stream().allMatch(JobPosting::isClosed)) {
            if (state != VacancyState.CLOSED) {
                this.closedAt = now;
            }
            this.state = VacancyState.CLOSED;
            return;
        }
        this.closedAt = null;
        this.state = postings.stream().anyMatch(posting -> posting.isConfirmed() && !posting.isClosed())
                ? VacancyState.ACTIVE : VacancyState.NEEDS_RECHECK;
    }

    public Long getId() {
        return id;
    }

    public VacancyState getState() {
        return state;
    }

    public String getTitle() {
        return title;
    }

    public String getPrimaryUrl() {
        return primaryUrl;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastConfirmedAt() {
        return lastConfirmedAt;
    }
}
