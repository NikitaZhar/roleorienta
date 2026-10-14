package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.roleorienta.worker.vacancy.PostingRecorder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Задание {@code PORTAL_RECORD}: вакансии источника портала (доска — IČO работодателя) из общего списка
 * ({@code portal_offer}) — без запросов к порталу (стенограмма §107). Вакансии работодателя, которые видели с начала
 * обхода, записываются обычной записью публикаций ({@link PostingRecorder}) с обходом источника ({@code crawl_run}):
 *
 * <ul>
 *   <li>после завершённого полного обхода — полное чтение: вакансии, которой нет, засчитывается отсутствие (закрытие
 *       — после N полных чтений подряд, то есть N обходов);</li>
 *   <li>после ленты «новые сверху» с новыми вакансиями работодателя — неполное ({@link PartialReason#NEW_OFFERS}):
 *       новые вакансии появляются сразу, отсутствие не засчитывается.</li>
 * </ul>
 */
@Component
public class PortalRecordHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PORTAL_RECORD";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourceRepository sources;
    private final PortalListRepository repository;
    private final PostingRecorder recorder;

    /**
     * @param sources    источники
     * @param repository вакансии общего списка
     * @param recorder   запись публикаций и обхода
     */
    public PortalRecordHandler(SourceRepository sources, PortalListRepository repository, PostingRecorder recorder) {
        this.sources = sources;
        this.repository = repository;
        this.recorder = recorder;
    }

    /**
     * Параметры задания.
     *
     * @param sourceId источник портала
     * @param cycle    номер обхода: вакансии, которые видели с его начала
     * @param complete полное чтение (завершённый обход) или неполное (лента)
     * @return JSON параметров
     */
    public static String payload(long sourceId, long cycle, boolean complete) {
        return JSON.createObjectNode().put("sourceId", sourceId).put("cycle", cycle).put("complete", complete)
                .toString();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = parse(task.payload());
        Optional<Source> found = sources.findById(payload.path("sourceId").asLong());
        if (found.isEmpty()) {
            return new TaskOutcome.Failed("Source not found");
        }
        Source source = found.get();
        List<FetchedPosting> postings = repository.postingsOf(source.getBoard(), payload.path("cycle").asLong());
        CrawlRun run = recorder.startRun(source, task.id());
        run.read(postings.size(), payload.path("complete").asBoolean() ? null : PartialReason.NEW_OFFERS);
        recorder.record(run, postings, Set.of());
        return new TaskOutcome.Done();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed PORTAL_RECORD payload: " + payload, exception);
        }
    }
}
