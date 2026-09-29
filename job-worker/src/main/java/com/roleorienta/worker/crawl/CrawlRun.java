package com.roleorienta.worker.crawl;

import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.source.Source;
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
import java.time.Instant;

/**
 * Обход — одно чтение списка публикаций источника (технический документ §4): когда, чем
 * кончилось и почему. Пишется в той же транзакции, что и публикации этого чтения, поэтому
 * результат чтения и его запись не расходятся. Строится после чтения, завершается
 * ({@link #finish}) при записи.
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

    /**
     * Для JPA.
     */
    protected CrawlRun() {
    }

    private CrawlRun(Source source, long taskId, Instant startedAt) {
        this.source = source;
        this.taskId = taskId;
        this.startedAt = startedAt;
    }

    /**
     * Список прочитан.
     *
     * @param source        источник
     * @param taskId        задание чтения
     * @param startedAt     начало чтения
     * @param postingsCount число прочитанных публикаций
     * @param partialReason причина неполноты; {@code null} — прочитан полностью
     * @return незавершённый обход {@code COMPLETE} или {@code PARTIAL}
     */
    public static CrawlRun read(Source source, long taskId, Instant startedAt, int postingsCount,
            PartialReason partialReason) {
        CrawlRun run = new CrawlRun(source, taskId, startedAt);
        run.state = partialReason == null ? CrawlRunState.COMPLETE : CrawlRunState.PARTIAL;
        run.partialReason = partialReason;
        run.postingsCount = postingsCount;
        return run;
    }

    /**
     * Источник не отдал список.
     *
     * @param source    источник
     * @param taskId    задание чтения
     * @param startedAt начало чтения
     * @param failure   временный или постоянный отказ
     * @return незавершённый обход {@code FAILED}; причина обрезается до длины колонки
     */
    public static CrawlRun failed(Source source, long taskId, Instant startedAt, HttpResult failure) {
        CrawlRun run = new CrawlRun(source, taskId, startedAt);
        run.state = CrawlRunState.FAILED;
        String reason = switch (failure) {
            case HttpResult.TemporaryFailure temporary -> {
                run.failureKind = TEMPORARY;
                yield temporary.reason();
            }
            case HttpResult.PermanentFailure permanent -> {
                run.failureKind = permanent.kind().name();
                yield permanent.reason();
            }
            case HttpResult.Success success -> {
                run.failureKind = TEMPORARY;
                yield "Unexpected success as failure";
            }
        };
        run.failureReason = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
        return run;
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
