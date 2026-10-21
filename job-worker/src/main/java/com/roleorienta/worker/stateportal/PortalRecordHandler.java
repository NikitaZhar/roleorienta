package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.adapter.stateportal.StatePortalProperties;
import com.roleorienta.worker.crawl.CrawlRun;
import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.roleorienta.worker.vacancy.PostingRecorder;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Задание {@code PORTAL_RECORD}: вакансии источника портала (доска — IČO работодателя) из общего списка
 * ({@code portal_offer}) — без запросов к порталу за списком (стенограмма §107). Вакансии работодателя, которые видели с начала
 * обхода, записываются обычной записью публикаций ({@link PostingRecorder}) с обходом источника ({@code crawl_run}):
 *
 * <ul>
 *   <li>после завершённого полного обхода — полное чтение: вакансии, которой нет, засчитывается отсутствие (закрытие
 *       — после N полных чтений подряд, то есть N обходов);</li>
 *   <li>после ленты «новые сверху» с новыми вакансиями работодателя — неполное ({@link PartialReason#NEW_OFFERS}):
 *       новые вакансии появляются сразу, отсутствие не засчитывается;</li>
 *   <li>после обхода с пропущенной страницей — неполное ({@link PartialReason#PAGE_FAILED}, §109).</li>
 * </ul>
 *
 * <p>Даты размещения в общем списке нет (§168): у публикаций, чья вакансия ещё без даты, дата берётся со страницы
 * объявления ({@link StatePortalAdapter#offerPostedDate}) — своей вакансии портала или объявления profesia.sk, не
 * больше {@code app.state-portal.dates-per-record} за задание, до записи (HTTP не держит транзакцию). Не получена —
 * следующая запись спросит снова; публикации без даты обходятся по кругу с места, зависящего от номера задания (как
 * у детали при чтении источника, §128, аудит §133). Пока даты нет, на экране — «найдена».</p>
 *
 * <p>Две записи одного источника одновременно (полная после обхода и неполная ленты) могут добавить одну и ту же
 * публикацию; вторая упирается в уникальность {@code (source_id, external_id)}, её транзакция откатывается — задание
 * повторяется позже, а не теряется (§109).</p>
 */
@Component
public class PortalRecordHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PORTAL_RECORD";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(PortalRecordHandler.class);

    private final SourceRepository sources;
    private final PortalListRepository repository;
    private final PostingRecorder recorder;
    private final StatePortalAdapter adapter;
    private final StatePortalProperties properties;

    /**
     * @param sources    источники
     * @param repository вакансии общего списка
     * @param recorder   запись публикаций и обхода
     * @param adapter    даты размещения со страниц объявлений
     * @param properties дат за задание
     */
    public PortalRecordHandler(SourceRepository sources, PortalListRepository repository, PostingRecorder recorder,
            StatePortalAdapter adapter, StatePortalProperties properties) {
        this.sources = sources;
        this.repository = repository;
        this.recorder = recorder;
        this.adapter = adapter;
        this.properties = properties;
    }

    /**
     * Параметры задания.
     *
     * @param sourceId источник портала
     * @param cycle    номер обхода: вакансии, которые видели с его начала
     * @param partial  причина неполного чтения; {@code null} — полное (завершённый обход без пропущенных страниц)
     * @return JSON параметров
     */
    public static String payload(long sourceId, long cycle, PartialReason partial) {
        return JSON.createObjectNode().put("sourceId", sourceId).put("cycle", cycle)
                .put("partial", partial == null ? null : partial.name()).toString();
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
        Map<String, Instant> postedAt = postedDates(source, postings, task.id());
        String partial = payload.path("partial").asText(null);
        CrawlRun run = recorder.startRun(source, task.id());
        run.read(postings.size(), partial == null ? null : PartialReason.valueOf(partial));
        try {
            recorder.record(run, postings, Set.of(), Set.of(), postedAt);
        } catch (DataIntegrityViolationException concurrent) {
            return new TaskOutcome.Retry("Concurrent record of source " + source.getId(), Duration.ZERO);
        }
        return new TaskOutcome.Done();
    }

    /**
     * Даты размещения со страниц объявлений у публикаций без даты (§168).
     *
     * @param seed номер задания — сдвиг начала обхода
     */
    private Map<String, Instant> postedDates(Source source, List<FetchedPosting> postings, long seed) {
        Set<String> dated = recorder.externalIdsWithPostedDate(source);
        List<FetchedPosting> undated = postings.stream().filter(posting -> !dated.contains(posting.externalId())
                && StatePortalAdapter.hasPostedDate(posting.externalId())).toList();
        int budget = Math.min(properties.datesPerRecord(), undated.size());
        int start = undated.isEmpty() ? 0 : (int) Math.floorMod(seed, (long) undated.size());
        Map<String, Instant> dates = new HashMap<>();
        for (int offset = 0; offset < budget; offset++) {
            FetchedPosting posting = undated.get((start + offset) % undated.size());
            adapter.offerPostedDate(posting.externalId(), posting.url())
                    .ifPresent(date -> dates.put(posting.externalId(), date));
        }
        if (budget > 0) {
            LOG.info("Portal source {} posted dates from offer pages: requested {}, received {}", source.getId(),
                    budget, dates.size());
        }
        return dates;
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed PORTAL_RECORD payload: " + payload, exception);
        }
    }
}
