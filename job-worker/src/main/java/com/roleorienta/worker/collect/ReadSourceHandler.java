package com.roleorienta.worker.collect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.roleorienta.worker.vacancy.PostingRecorder;
import java.util.ArrayList;
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
 * <p>Если список не содержит текста публикации, текст запрашивается у адаптера отдельно — только
 * для публикаций без сохранённого текста и не больше
 * {@link CollectProperties#maxContentRequestsPerRead()} за чтение, чтобы задание оставалось
 * ограниченным. Не полученный текст запрашивается при следующем чтении.</p>
 */
@Component
public class ReadSourceHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "READ_SOURCE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = LoggerFactory.getLogger(ReadSourceHandler.class);

    private final SourceRepository sources;
    private final Map<String, SourceAdapter> adaptersByProvider;
    private final PostingRecorder recorder;
    private final CollectProperties properties;

    /**
     * @param sources  доступ к источникам
     * @param adapters все адаптеры провайдеров
     * @param recorder   запись публикаций
     * @param properties настройки сбора
     */
    public ReadSourceHandler(SourceRepository sources, ObjectProvider<SourceAdapter> adapters,
            PostingRecorder recorder, CollectProperties properties) {
        this.sources = sources;
        this.adaptersByProvider = adapters.orderedStream()
                .collect(Collectors.toMap(SourceAdapter::provider, Function.identity()));
        this.recorder = recorder;
        this.properties = properties;
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
        return switch (adapter.read(source.getBoard())) {
            case SourceReadResult.Read read -> {
                LOG.info("Source {} read: {} postings, complete={}", source.getId(), read.postings().size(),
                        read.complete());
                recorder.record(source, withContent(adapter, source, read.postings()), read.complete());
                yield new TaskOutcome.Done();
            }
            case SourceReadResult.Unavailable unavailable -> {
                recorder.recordUnavailable(source);
                yield toOutcome(unavailable.failure());
            }
        };
    }

    private List<FetchedPosting> withContent(SourceAdapter adapter, Source source, List<FetchedPosting> fetched) {
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
            String content = adapter.content(source.getBoard(), posting.externalId());
            if (content != null) {
                received++;
            }
            result.add(new FetchedPosting(posting.externalId(), posting.title(), posting.url(),
                    posting.location(), content));
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
