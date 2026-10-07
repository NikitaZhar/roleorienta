package com.roleorienta.worker.collect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.PostingCheck;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.snapshot.SnapshotStore;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.roleorienta.worker.vacancy.PostingRecorder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Задание {@code READ_SOURCE}: прочитать доску источника адаптером провайдера и записать
 * публикации. Параметры задания: {@code {"sourceId": N}}.
 *
 * <p>Отказ источника переводится в результат задания: временный — повтор позже (с учётом
 * {@code Retry-After}), постоянный — неудача. При отказе сохранённые сведения не меняются,
 * вакансии источника переходят в «нуждается в повторной проверке» и не закрываются
 * (бизнес-описание §4.3, §4.6).</p>
 *
 * <p>Если список не содержит текста публикации, у адаптера отдельно запрашивается деталь — текст и
 * места (место детали заменяет место списка) — только для публикаций без сохранённого текста и не
 * больше
 * {@link CollectProperties#maxContentRequestsPerRead()} за чтение, чтобы задание оставалось
 * ограниченным. Не полученный текст запрашивается при следующем чтении.</p>
 *
 * <p>Каждое чтение записывается обходом ({@link CrawlRun}): от начала чтения до записи
 * результата, включая запросы текста. Тела ответов списка сохраняются снимками в хранилище до
 * записи в БД; обход ссылается на них.</p>
 *
 * <p>Источник со страной читается с фильтром по стране. Пропавшая из такого списка известная
 * публикация проверяется отдельно ({@link SourceAdapter#check}, не больше
 * {@link CollectProperties#maxChecksPerRead()} за чтение): есть — сведения обновляются, нет —
 * отсутствие засчитывается, не проверена — не засчитывается (бизнес-описание §4.3).</p>
 */
@Component
public class ReadSourceHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "READ_SOURCE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Длина {@code source.employer_name}. */
    private static final int MAX_EMPLOYER_NAME = 300;

    private static final Logger LOG = LoggerFactory.getLogger(ReadSourceHandler.class);

    private final SourceRepository sources;
    private final Map<String, SourceAdapter> adaptersByProvider;
    private final PostingRecorder recorder;
    private final CollectProperties properties;
    private final SnapshotStore snapshots;

    /**
     * @param sources  доступ к источникам
     * @param adapters все адаптеры провайдеров
     * @param recorder   запись публикаций
     * @param properties настройки сбора
     * @param snapshots  хранилище снимков ответов
     */
    public ReadSourceHandler(SourceRepository sources, ObjectProvider<SourceAdapter> adapters,
            PostingRecorder recorder, CollectProperties properties, SnapshotStore snapshots) {
        this.sources = sources;
        this.adaptersByProvider = adapters.orderedStream()
                .collect(Collectors.toMap(SourceAdapter::provider, Function.identity()));
        this.recorder = recorder;
        this.properties = properties;
        this.snapshots = snapshots;
    }

    /**
     * Параметры задания чтения источника.
     *
     * @param sourceId источник
     * @return JSON параметров
     */
    public static String payload(long sourceId) {
        return "{\"sourceId\":" + sourceId + "}";
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        Optional<Source> found = sources.findById(sourceId(task));
        if (found.isEmpty()) {
            return new TaskOutcome.Failed("Source not found");
        }
        Source source = found.get();
        SourceAdapter adapter = adaptersByProvider.get(source.getProvider());
        if (adapter == null) {
            return new TaskOutcome.Failed("No adapter for provider " + source.getProvider());
        }
        CrawlRun run = recorder.startRun(source, task.id());
        return switch (adapter.read(source.getBoard(), source.getCountry())) {
            case SourceReadResult.Read read -> {
                LOG.info("Source {} read: {} postings, partialReason={}", source.getId(), read.postings().size(),
                        read.partialReason());
                storeSnapshots(source, run, read.responses());
                List<FetchedPosting> postings = new ArrayList<>(read.postings());
                Set<String> unverified = new HashSet<>();
                if (read.complete() && source.getCountry() != null) {
                    checkMissing(adapter, source, postings, unverified);
                }
                postings = withDetail(adapter, source, postings);
                nameEmployer(adapter, source, postings);
                run.read(postings.size(), read.partialReason());
                recorder.record(run, postings, unverified);
                yield new TaskOutcome.Done();
            }
            case SourceReadResult.Unavailable unavailable -> {
                run.fail(unavailable.failure());
                recorder.recordUnavailable(run);
                yield toOutcome(unavailable.failure());
            }
        };
    }

    /**
     * Тела ответов — в хранилище снимков, ссылки на них — в обход (порядок «объект, затем ссылка»).
     */
    private void storeSnapshots(Source source, CrawlRun run, List<String> responses) {
        for (String response : responses) {
            snapshots.put(source.getId(), response)
                    .ifPresent(stored -> run.addSnapshot(stored.objectKey(), stored.sha256()));
        }
    }

    /**
     * Проверяет известные незакрытые публикации, которых нет в отфильтрованном списке: найденные
     * добавляются в {@code postings}, непроверенные — в {@code unverified}.
     */
    private void checkMissing(SourceAdapter adapter, Source source, List<FetchedPosting> postings,
            Set<String> unverified) {
        Set<String> listed = new HashSet<>();
        postings.forEach(posting -> listed.add(posting.externalId()));
        int budget = properties.maxChecksPerRead();
        for (String externalId : recorder.openExternalIds(source)) {
            if (listed.contains(externalId)) {
                continue;
            }
            PostingCheck check = budget-- > 0 ? adapter.check(source.getBoard(), externalId)
                    : new PostingCheck.Unknown("Check limit reached");
            switch (check) {
                case PostingCheck.Present present -> postings.add(present.posting());
                case PostingCheck.Absent absent -> LOG.info("Source {} posting {} absent", source.getId(), externalId);
                case PostingCheck.Unknown unknown -> {
                    LOG.warn("Source {} posting {} unverified: {}", source.getId(), externalId, unknown.reason());
                    unverified.add(externalId);
                }
            }
        }
    }

    /**
     * Источник без компании (доска обратного пути) получает название работодателя от системы найма —
     * один раз, пока его нет (аудит §65: иначе в сведениях о вакансии работодатель «не указан», бизнес-описание §6).
     */
    private void nameEmployer(SourceAdapter adapter, Source source, List<FetchedPosting> postings) {
        if (postings.isEmpty() || !sources.needsEmployerName(source.getId())) {
            return;
        }
        adapter.employerName(source.getBoard(), postings.get(0).externalId())
                .map(name -> name.length() > MAX_EMPLOYER_NAME ? name.substring(0, MAX_EMPLOYER_NAME) : name)
                .ifPresent(name -> {
                    sources.nameEmployer(source.getId(), name);
                    LOG.info("Source {} employer named by provider: {}", source.getId(), name);
                });
    }

    private List<FetchedPosting> withDetail(SourceAdapter adapter, Source source, List<FetchedPosting> fetched) {
        Set<String> haveContent = recorder.externalIdsWithContent(source);
        int budget = properties.maxContentRequestsPerRead();
        int received = 0;
        List<FetchedPosting> result = new ArrayList<>(fetched.size());
        for (FetchedPosting posting : fetched) {
            if (posting.content() != null || budget == 0 || haveContent.contains(posting.externalId())) {
                result.add(posting);
                continue;
            }
            budget--;
            FetchedPosting detail = adapter.detail(source.getBoard(), posting.externalId());
            String content = detail == null ? null : detail.content();
            if (content != null) {
                received++;
            }
            String location = detail != null && detail.location() != null ? detail.location() : posting.location();
            result.add(new FetchedPosting(posting.externalId(), posting.title(), posting.url(), location, content));
        }
        int requested = properties.maxContentRequestsPerRead() - budget;
        if (requested > 0) {
            LOG.info("Source {} text: requested {}, received {}", source.getId(), requested, received);
        }
        return result;
    }

    private static TaskOutcome toOutcome(HttpResult failure) {
        return switch (failure) {
            case HttpResult.TemporaryFailure temporary ->
                    new TaskOutcome.Retry(temporary.reason(), temporary.retryAfter());
            case HttpResult.PermanentFailure permanent ->
                    new TaskOutcome.Failed(permanent.kind() + ": " + permanent.reason());
            case HttpResult.Success success -> new TaskOutcome.Failed("Unexpected success as failure");
        };
    }

    private static long sourceId(TaskRecord task) {
        try {
            return JSON.readTree(task.payload()).path("sourceId").asLong();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed READ_SOURCE payload: " + task.payload(), exception);
        }
    }
}
