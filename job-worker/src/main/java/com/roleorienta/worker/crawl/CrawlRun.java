package com.roleorienta.worker.crawl;

import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.source.Source;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * Обход — одно чтение списка публикаций источника (технический документ §4): когда, чем
 * кончилось и почему. Пишется в той же транзакции, что и публикации этого чтения, поэтому
 * результат чтения и его запись не расходятся. Начинается ({@link #start}) до чтения, получает
 * итог после него и завершается ({@link #finish}) при записи. Ссылки на снимки ответов
 * ({@link SourceSnapshot}) сохраняются вместе с обходом ({@code cascade = PERSIST}).
 */
@Entity
@Table(name = "crawl_run")
public class CrawlRun {

    /** Длина {@code failure_reason} в схеме. */
    private static final int MAX_REASON_LENGTH = 1000;

    /** {@code failure_kind} временного отказа. */
    private static final String TEMPORARY = "TEMPORARY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id")
    private Source source;

    private Long taskId;

    private Instant startedAt;

    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    private CrawlRunState state;

    @Enumerated(EnumType.STRING)
    private PartialReason partialReason;

    private String failureKind;

    private String failureReason;

    private int postingsCount;

    @OneToMany(mappedBy = "crawlRun", cascade = CascadeType.PERSIST)
    private List<SourceSnapshot> snapshots = new ArrayList<>();

    /**
     * Для JPA.
     */
    protected CrawlRun() {
    }

    /**
     * Начало обхода — до первого запроса к источнику.
     *
     * @param source    источник
     * @param taskId    задание чтения
     * @param startedAt начало чтения
     * @return обход без итога; итог — {@link #read} или {@link #fail}
     */
    public static CrawlRun start(Source source, long taskId, Instant startedAt) {
        CrawlRun run = new CrawlRun();
        run.source = source;
        run.taskId = taskId;
        run.startedAt = startedAt;
        return run;
    }

    /**
     * Итог: список прочитан.
     *
     * @param count  число прочитанных публикаций
     * @param reason причина неполноты; {@code null} — прочитан полностью
     */
    public void read(int count, PartialReason reason) {
        this.state = reason == null ? CrawlRunState.COMPLETE : CrawlRunState.PARTIAL;
        this.partialReason = reason;
        this.postingsCount = count;
    }

    /**
     * Итог: источник не отдал список.
     *
     * @param failure временный или постоянный отказ; причина обрезается до длины колонки
     */
    public void fail(HttpResult failure) {
        this.state = CrawlRunState.FAILED;
        String reason = switch (failure) {
            case HttpResult.TemporaryFailure temporary -> {
                this.failureKind = TEMPORARY;
                yield temporary.reason();
            }
            case HttpResult.PermanentFailure permanent -> {
                this.failureKind = permanent.kind().name();
                yield permanent.reason();
            }
            case HttpResult.Success success -> {
                this.failureKind = TEMPORARY;
                yield "Unexpected success as failure";
            }
        };
        this.failureReason = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
    }

    /**
     * Ссылка на снимок ответа, уже сохранённый в хранилище (порядок «объект, затем ссылка в БД»).
     *
     * @param objectKey ключ объекта
     * @param sha256    хеш тела ответа
     */
    public void addSnapshot(String objectKey, String sha256) {
        snapshots.add(new SourceSnapshot(this, objectKey, sha256));
    }

    /**
     * Отмечает конец обхода — момент записи его результата.
     *
     * @param now время записи
     * @return этот обход
     */
    public CrawlRun finish(Instant now) {
        this.finishedAt = now;
        return this;
    }

    /**
     * @return {@code true} — список прочитан полностью
     */
    public boolean isComplete() {
        return state == CrawlRunState.COMPLETE;
    }

    public Source getSource() {
        return source;
    }
}
