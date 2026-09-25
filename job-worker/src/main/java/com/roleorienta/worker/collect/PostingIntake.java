package com.roleorienta.worker.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlRun;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.outbox.OutboxEvent;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import com.roleorienta.worker.scheduling.CrawlTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Приём публикаций, обнаруженных в ленте (§6, §63): все сохраняются идемпотентно, а задание
 * {@code FETCH_POSTING} (отдельный запрос детали) ставится только для заголовков ниши
 * ({@link NicheFilterProperties}) и не больше дневного бюджета на источник; новые (ещё без
 * детали) — первыми, перечитывание уже известных — на остаток бюджета. Задание и outbox-событие
 * создаются в транзакции вызывающего. Вынесено из {@link DiscoverPageJobHandler} (§78).
 */
@Component
public class PostingIntake {

    private final JobPostingRepository jobPostingRepository;
    private final CrawlTaskRepository crawlTaskRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final NicheFilterProperties niche;
    private final Clock clock;

    /** Формирование тел заданий (JSON). Создаётся локально, потокобезопасен. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param jobPostingRepository  публикации
     * @param crawlTaskRepository   задания (бюджет за сутки, новые FETCH_POSTING)
     * @param outboxEventRepository outbox (для постановки FETCH_POSTING)
     * @param niche                 ниша и дневной бюджет деталей (§63)
     * @param clock                 часы (граница суток бюджета — UTC)
     */
    public PostingIntake(JobPostingRepository jobPostingRepository, CrawlTaskRepository crawlTaskRepository,
                         OutboxEventRepository outboxEventRepository, NicheFilterProperties niche, Clock clock) {
        this.jobPostingRepository = jobPostingRepository;
        this.crawlTaskRepository = crawlTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.niche = niche;
        this.clock = clock;
    }

    /**
     * Сохраняет публикации ленты и ставит задания на детали ниши в пределах бюджета.
     *
     * @param run        обход, в котором ставятся задания
     * @param sourceId   источник
     * @param discovered публикации ленты
     * @return сколько публикаций в нише и сколько заданий поставлено
     */
    public Intake accept(CrawlRun run, Long sourceId, List<DiscoveredPosting> discovered) {
        Instant now = clock.instant();
        for (DiscoveredPosting posting : discovered) {
            jobPostingRepository.upsert(sourceId, posting.externalId(), posting.url(), posting.rawTitle(), now);
        }
        List<String> nicheIds = discovered.stream()
                .filter(posting -> niche.matches(posting.rawTitle()))
                .map(DiscoveredPosting::externalId)
                .distinct()
                .toList();
        List<String> toFetch = withinBudget(sourceId, nicheIds, now);
        for (String externalId : toFetch) {
            scheduleFetch(run, sourceId, externalId);
        }
        return new Intake(nicheIds.size(), toFetch.size());
    }

    /**
     * Итог приёма.
     *
     * @param inNiche   публикаций в нише
     * @param scheduled поставлено заданий {@code FETCH_POSTING}
     */
    public record Intake(int inNiche, int scheduled) {
    }

    /**
     * Публикации ниши в пределах остатка дневного бюджета деталей источника: сначала новые
     * (без детали), затем уже известные (перечитывание ради изменений).
     */
    private List<String> withinBudget(Long sourceId, List<String> nicheIds, Instant now) {
        if (nicheIds.isEmpty()) {
            return List.of();
        }
        Instant dayStart = now.truncatedTo(ChronoUnit.DAYS);
        long used = crawlTaskRepository.countByTypeForSourceSince(CrawlTaskType.FETCH_POSTING, sourceId, dayStart);
        int remaining = (int) Math.max(0, niche.dailyDetailBudget() - used);
        if (remaining == 0) {
            return List.of();
        }
        Set<String> detailed = new HashSet<>(jobPostingRepository.findDetailedExternalIds(sourceId, nicheIds));
        List<String> ordered = new ArrayList<>(nicheIds.size());
        nicheIds.stream().filter(id -> !detailed.contains(id)).forEach(ordered::add);
        nicheIds.stream().filter(detailed::contains).forEach(ordered::add);
        return ordered.subList(0, Math.min(remaining, ordered.size()));
    }

    /**
     * Ставит задание {@code FETCH_POSTING} на одну публикацию: задание в том же обходе и
     * outbox-событие (в текущей транзакции); обработает его {@code FetchPostingJobHandler}.
     */
    private void scheduleFetch(CrawlRun run, Long sourceId, String externalId) {
        CrawlTask fetchTask = crawlTaskRepository.save(
                new CrawlTask(run, CrawlTaskType.FETCH_POSTING, CrawlTaskState.SCHEDULED));
        outboxEventRepository.save(new OutboxEvent(
                "CrawlTask",
                String.valueOf(fetchTask.getId()),
                CrawlTaskType.FETCH_POSTING.name(),
                fetchPayload(fetchTask.getId(), run.getId(), sourceId, externalId),
                null));
    }

    /** JSON-тело задания {@code FETCH_POSTING} (несёт идентификатор публикации). */
    private String fetchPayload(Long taskId, Long crawlRunId, Long sourceId, String externalId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("crawlRunId", crawlRunId);
        body.put("sourceId", sourceId);
        body.put("externalId", externalId);
        body.put("type", CrawlTaskType.FETCH_POSTING.name());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сформировать payload FETCH_POSTING", exception);
        }
    }
}
