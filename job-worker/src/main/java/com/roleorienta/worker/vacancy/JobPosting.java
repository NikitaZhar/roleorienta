package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.source.Source;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Публикация — запись вакансии в одном источнике; уникальна в паре «источник + внешний id».
 *
 * <p>{@link ManyToOne} с {@code LAZY} — связанная строка загружается только при обращении.
 * Полный текст ({@code content}) хранится для отбора по содержанию и не показывается
 * пользователю (бизнес-описание §10).</p>
 *
 * <p>{@link OneToMany} с {@code mappedBy} — история публикации ({@link VacancyRevision}) хранится в
 * её таблице со ссылкой на публикацию; {@code cascade = PERSIST} сохраняет новые записи вместе с
 * публикацией. Добавление записи не загружает уже сохранённую историю.
 * https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html#associations-one-to-many</p>
 */
@Entity
@Table(name = "job_posting")
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id")
    private Source source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vacancy_id")
    private Vacancy vacancy;

    private String externalId;

    private String title;

    private String url;

    private String location;

    private String content;

    private Instant firstSeenAt;

    private Instant lastConfirmedAt;

    private boolean confirmed;

    private int missingCompleteReads;

    private Instant closedAt;

    @OneToMany(mappedBy = "jobPosting", cascade = CascadeType.PERSIST)
    private List<VacancyRevision> revisions = new ArrayList<>();

    /**
     * Для JPA.
     */
    protected JobPosting() {
    }

    /**
     * Новая публикация.
     *
     * @param source  источник
     * @param vacancy вакансия, к которой относится публикация
     * @param fetched данные публикации из источника
     * @param seenAt  момент обнаружения
     */
    public JobPosting(Source source, Vacancy vacancy, FetchedPosting fetched, Instant seenAt) {
        this.source = source;
        this.vacancy = vacancy;
        this.externalId = fetched.externalId();
        this.firstSeenAt = seenAt;
        apply(fetched, seenAt);
    }

    /**
     * Полный обход показал публикацию: изменения позиции и места и повторное появление закрытой
     * публикации записываются в историю, затем сведения обновляются (см. {@link #apply}); изменились
     * название или текст — соответствие вакансии позициям пересчитывается.
     *
     * @param fetched     данные публикации из источника
     * @param run         полный обход; уже сохранён
     * @param confirmedAt момент подтверждения
     */
    public void update(FetchedPosting fetched, CrawlRun run, Instant confirmedAt) {
        if (closedAt != null) {
            revisions.add(new VacancyRevision(this, run, RevisionField.REOPENED, null, null));
        }
        if (!Objects.equals(title, fetched.title())) {
            revisions.add(new VacancyRevision(this, run, RevisionField.TITLE, title, fetched.title()));
        }
        if (!Objects.equals(location, fetched.location())) {
            revisions.add(new VacancyRevision(this, run, RevisionField.LOCATION, location, fetched.location()));
        }
        boolean textChanged = !Objects.equals(title, fetched.title()) || !Objects.equals(location, fetched.location())
                || fetched.content() != null && !Objects.equals(content, fetched.content());
        apply(fetched, confirmedAt);
        if (textChanged) {
            vacancy.resetMatch();
        }
    }

    /**
     * Сведения обновлены (текст — если получен), наличие подтверждено, счётчик отсутствия сброшен;
     * закрытая ранее публикация снова открыта (та же вакансия).
     */
    private void apply(FetchedPosting fetched, Instant confirmedAt) {
        this.title = fetched.title();
        this.url = fetched.url();
        this.location = fetched.location();
        if (fetched.content() != null) {
            this.content = fetched.content();
        }
        this.lastConfirmedAt = confirmedAt;
        this.confirmed = true;
        this.missingCompleteReads = 0;
        this.closedAt = null;
    }

    /**
     * Полное чтение источника публикацию не показало: подтверждения нет; после
     * {@code closeAfter} таких чтений подряд публикация закрыта.
     *
     * @param closeAfter число полных чтений без публикации до закрытия
     * @param now        момент чтения
     */
    public void markMissing(int closeAfter, Instant now) {
        this.confirmed = false;
        this.missingCompleteReads++;
        if (missingCompleteReads >= closeAfter && closedAt == null) {
            this.closedAt = now;
        }
    }

    /**
     * Источник не прочитан (отказ): подтверждения нет, счётчик отсутствия не меняется.
     */
    public void markUnconfirmed() {
        this.confirmed = false;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    public String getExternalId() {
        return externalId;
    }

    public Vacancy getVacancy() {
        return vacancy;
    }
}
