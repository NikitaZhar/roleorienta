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
 * <p>Даты размещения (§168) и профессии (§203) в общем списке нет: они берутся со страницы объявления
 * ({@link StatePortalAdapter#offerPage}) у публикаций, чья вакансия ещё без даты (своя вакансия портала или объявление
 * profesia.sk) или у которых ещё нет кода профессии (своя вакансия портала — «Profesia (SK ISCO-08)»), не больше
 * {@code app.state-portal.offer-pages-per-record} страниц за задание, до записи (HTTP не держит транзакцию). Не
 * получено — следующая запись спросит снова; такие публикации обходятся по кругу с места, зависящего от номера задания
 * (как у детали при чтении источника, §128, аудит §133): новые получают сведения за одну-две записи, накопленные —
 * постепенно (решение владельца 2026-10-26: можно и медленнее). Даты уходят в запись публикаций, коды профессий —
 * после неё в {@code job_posting.occupation_code} (новая публикация до записи ещё не существует).</p>
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
     * @param adapter    даты размещения и профессии со страниц объявлений
     * @param properties страниц объявлений за задание
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
        OfferFacts facts = offerFacts(source, postings, task.id());
        String partial = payload.path("partial").asText(null);
        CrawlRun run = recorder.startRun(source, task.id());
        run.read(postings.size(), partial == null ? null : PartialReason.valueOf(partial));
        try {
            recorder.record(run, postings, Set.of(), Set.of(), facts.postedAt());
        } catch (DataIntegrityViolationException concurrent) {
            return new TaskOutcome.Retry("Concurrent record of source " + source.getId(), Duration.ZERO);
        }
        repository.recordOccupations(source.getId(), facts.occupations());
        return new TaskOutcome.Done();
    }

    /**
     * Даты размещения (§168) и коды профессий (§203) со страниц объявлений у публикаций, у которых их ещё нет.
     *
     * @param seed номер задания — сдвиг начала обхода
     */
    private OfferFacts offerFacts(Source source, List<FetchedPosting> postings, long seed) {
        Set<String> dated = recorder.externalIdsWithPostedDate(source);
        Set<String> coded = repository.externalIdsWithOccupation(source.getId());
        List<FetchedPosting> missing = postings.stream().filter(posting -> {
            String id = posting.externalId();
            return !dated.contains(id) && StatePortalAdapter.hasPostedDate(id)
                    || !coded.contains(id) && StatePortalAdapter.hasOccupation(id);
        }).toList();
        int budget = Math.min(properties.offerPagesPerRecord(), missing.size());
        int start = missing.isEmpty() ? 0 : (int) Math.floorMod(seed, (long) missing.size());
        Map<String, Instant> dates = new HashMap<>();
        Map<String, String> occupations = new HashMap<>();
        for (int offset = 0; offset < budget; offset++) {
            FetchedPosting posting = missing.get((start + offset) % missing.size());
            adapter.offerPage(posting.externalId(), posting.url()).ifPresent(page -> {
                if (page.postedAt() != null) {
                    dates.put(posting.externalId(), page.postedAt());
                }
                if (page.occupation() != null) {
                    occupations.put(posting.externalId(), page.occupation());
                }
            });
        }
        if (budget > 0) {
            LOG.info("Portal source {} offer pages: requested {}, posted dates {}, occupations {}", source.getId(),
                    budget, dates.size(), occupations.size());
        }
        return new OfferFacts(dates, occupations);
    }

    /**
     * Сведения со страниц объявлений.
     *
     * @param postedAt    даты размещения по внешнему id
     * @param occupations группы ISCO-08 по внешнему id
     */
    private record OfferFacts(Map<String, Instant> postedAt, Map<String, String> occupations) {
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed PORTAL_RECORD payload: " + payload, exception);
        }
    }
}
