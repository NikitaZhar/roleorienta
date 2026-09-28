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
import com.roleorienta.worker.vacancy.PostingRecorder;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Задание {@code READ_SOURCE}: прочитать доску источника адаптером провайдера и записать
 * публикации. Параметры задания: {@code {"sourceId": N}}.
 *
 * <p>Отказ источника переводится в результат задания: временный — повтор позже (с учётом
 * {@code Retry-After}), постоянный — неудача. Сохранённые сведения при отказе не меняются
 * (бизнес-описание §4.6).</p>
 */
@Component
public class ReadSourceHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "READ_SOURCE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourceRepository sources;
    private final Map<String, SourceAdapter> adaptersByProvider;
    private final PostingRecorder recorder;

    /**
     * @param sources  доступ к источникам
     * @param adapters все адаптеры провайдеров
     * @param recorder запись публикаций
     */
    public ReadSourceHandler(SourceRepository sources, ObjectProvider<SourceAdapter> adapters,
            PostingRecorder recorder) {
        this.sources = sources;
        this.adaptersByProvider = adapters.orderedStream()
                .collect(Collectors.toMap(SourceAdapter::provider, Function.identity()));
        this.recorder = recorder;
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
                recorder.record(source, read.postings());
                yield new TaskOutcome.Done();
            }
            case SourceReadResult.Unavailable unavailable -> toOutcome(unavailable.failure());
        };
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
