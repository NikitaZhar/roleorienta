package com.roleorienta.worker.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlRun;
import com.roleorienta.core.domain.CrawlRunState;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.jobs.TypedJobHandler;
import com.roleorienta.worker.outbox.OutboxEvent;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import com.roleorienta.worker.scheduling.CrawlRunRepository;
import com.roleorienta.worker.scheduling.CrawlTaskRepository;
import com.roleorienta.worker.scheduling.SourceRepository;
import com.roleorienta.worker.adapters.MarketScope;
import com.roleorienta.worker.discovery.DiscoveryMarketProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Обработчик задания {@code DISCOVER_PAGE} — первое звено конвейера сбора (§6 техдока).
 *
 * <p>Читает ленту источника через адаптер, идемпотентно сохраняет обнаруженные
 * публикации и для каждой ставит задание {@code FETCH_POSTING} (дозапрос детали) —
 * задание и outbox-событие создаются в той же транзакции, тем же паттерном, что у
 * планировщика. Так первое звено запускает второе через штатную магистраль (§13).
 * В конце отмечает обход и своё задание завершёнными.</p>
 *
 * <p><b>Только рынок и пагинация (§62).</b> Лента читается в области целевого рынка
 * ({@link DiscoveryMarketProperties#toScope()}): адаптер, умеющий фильтровать на стороне
 * провайдера (Workday), отдаёт только публикации SK/AT — у крупного работодателя это
 * единицы-десятки вместо тысяч. Страницы читаются по курсору до его конца, но не больше
 * {@code app.collect.max-list-pages} за обход (бюджет запросов, A29).</p>
 *
 * <p><b>Ниша и бюджет деталей (§63).</b> В ленту сохраняются все публикации рынка, но
 * {@code FETCH_POSTING} (отдельный запрос детали) ставится только для заголовков ниши
 * ({@link NicheFilterProperties}) и не больше дневного бюджета на источник; новые
 * (ещё без детали) — первыми, перечитывание уже известных — на остаток бюджета.</p>
 *
 * <p><b>Границы транзакции (пилот, Этап 1).</b> Метод вызывается внутри транзакции
 * слушателя (общей с фиксацией ключа идемпотентности), поэтому HTTP-вызов адаптера
 * происходит в этой же транзакции. Разнесение HTTP и записи в БД по разным
 * транзакциям (§6, шаг 4) — улучшение Этапа 2, вынесено отдельной задачей.</p>
 */
@Component
public class DiscoverPageJobHandler implements TypedJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DiscoverPageJobHandler.class);

    private final SourceRepository sourceRepository;
    private final CrawlRunRepository crawlRunRepository;
    private final CrawlTaskRepository crawlTaskRepository;
    private final JobPostingRepository jobPostingRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final SourceAdapterRegistry adapterRegistry;
    private final MarketScope marketScope;
    private final int maxListPages;
    private final NicheFilterProperties niche;
    private final Clock clock;

    /** Разбор и формирование тел заданий (JSON). Создаётся локально (как в {@code SourceScheduler}). */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param sourceRepository      источники
     * @param crawlRunRepository    обходы
     * @param crawlTaskRepository   задания
     * @param jobPostingRepository  публикации
     * @param outboxEventRepository outbox (для постановки FETCH_POSTING)
     * @param adapterRegistry       реестр адаптеров источников
     * @param market                целевой рынок (область сбора, §62)
     * @param maxListPages          потолок страниц ленты за один обход
     * @param niche                 ниша и дневной бюджет деталей (§63)
     * @param clock                 часы (граница суток бюджета — UTC)
     */
    public DiscoverPageJobHandler(
            SourceRepository sourceRepository,
            CrawlRunRepository crawlRunRepository,
            CrawlTaskRepository crawlTaskRepository,
            JobPostingRepository jobPostingRepository,
            OutboxEventRepository outboxEventRepository,
            SourceAdapterRegistry adapterRegistry,
            DiscoveryMarketProperties market,
            @Value("${app.collect.max-list-pages:10}") int maxListPages,
            NicheFilterProperties niche,
            Clock clock) {
        this.sourceRepository = sourceRepository;
        this.crawlRunRepository = crawlRunRepository;
        this.crawlTaskRepository = crawlTaskRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.adapterRegistry = adapterRegistry;
        this.marketScope = market.toScope();
        this.maxListPages = maxListPages;
        this.niche = niche;
        this.clock = clock;
    }

    @Override
    public String taskType() {
        return CrawlTaskType.DISCOVER_PAGE.name();
    }

    @Override
    public void handle(JobMessage message) {
        Payload payload = parse(message.payload());

        Source source = sourceRepository.findById(payload.sourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Источник не найден: id=" + payload.sourceId()));
        CrawlRun run = crawlRunRepository.findById(payload.crawlRunId())
                .orElseThrow(() -> new IllegalStateException(
                        "Обход не найден: id=" + payload.crawlRunId()));
        if (source.getState() != SourceState.ACTIVE) {
            // Источник поставлен на паузу/отключён после постановки задания (§60): обход не идёт,
            // FETCH_POSTING не порождаются.
            run.setState(CrawlRunState.FAILED);
            crawlRunRepository.save(run);
            CrawlTask task = crawlTaskRepository.findById(payload.taskId())
                    .orElseThrow(() -> new IllegalStateException("Задание не найдено: id=" + payload.taskId()));
            task.setState(CrawlTaskState.FAILED);
            crawlTaskRepository.save(task);
            log.info("DISCOVER_PAGE: источник {} в состоянии {} — обход снят без запроса",
                    source.getId(), source.getState());
            return;
        }

        SourceAdapter adapter = adapterRegistry.forProviderCode(source.getProvider().getCode());
        List<DiscoveredPosting> discovered = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            PostingsPage page = adapter.listPostings(source, cursor, marketScope);
            discovered.addAll(page.postings());
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < maxListPages);
        if (cursor != null) {
            log.info("DISCOVER_PAGE: источник {} — достигнут потолок {} страниц, остаток — в следующем обходе",
                    source.getId(), maxListPages);
        }

        Instant now = clock.instant();
        for (DiscoveredPosting posting : discovered) {
            jobPostingRepository.upsert(
                    source.getId(),
                    posting.externalId(),
                    posting.url(),
                    posting.rawTitle(),
                    now);
        }
        List<String> nicheIds = discovered.stream()
                .filter(p -> niche.matches(p.rawTitle()))
                .map(DiscoveredPosting::externalId)
                .distinct()
                .toList();
        List<String> toFetch = withinBudget(source.getId(), nicheIds, now);
        for (String externalId : toFetch) {
            scheduleFetch(run, source.getId(), externalId);
        }

        run.setState(CrawlRunState.COMPLETED);
        crawlRunRepository.save(run);

        CrawlTask task = crawlTaskRepository.findById(payload.taskId())
                .orElseThrow(() -> new IllegalStateException("Задание не найдено: id=" + payload.taskId()));
        task.setState(CrawlTaskState.SUCCEEDED);
        crawlTaskRepository.save(task);

        log.info("DISCOVER_PAGE: источник {} ({}){}, страниц {}, обнаружено публикаций {}, в нише {}, "
                        + "поставлено FETCH_POSTING {}{}",
                source.getId(), source.getProvider().getCode(), marketScope.restricted() ? " [рынок]" : "",
                pages, discovered.size(), nicheIds.size(), toFetch.size(),
                toFetch.size() < nicheIds.size() ? " (остальные — вне дневного бюджета)" : "");
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
     * Ставит задание {@code FETCH_POSTING} на одну публикацию: создаёт задание в том
     * же обходе и outbox-событие (в текущей транзакции). Событие далее доставляется
     * магистралью, а обработает его {@code FetchPostingJobHandler}.
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

    /**
     * Формирует JSON-тело задания {@code FETCH_POSTING} (несёт идентификатор публикации).
     */
    private String fetchPayload(Long taskId, Long crawlRunId, Long sourceId, String externalId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("crawlRunId", crawlRunId);
        body.put("sourceId", sourceId);
        body.put("externalId", externalId);
        body.put("type", CrawlTaskType.FETCH_POSTING.name());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сформировать payload FETCH_POSTING", e);
        }
    }

    /**
     * Разбирает тело задания, сформированное планировщиком.
     */
    private Payload parse(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return new Payload(
                    node.path("taskId").asLong(),
                    node.path("crawlRunId").asLong(),
                    node.path("sourceId").asLong());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать тело задания DISCOVER_PAGE", e);
        }
    }

    /**
     * Разобранное тело задания: идентификаторы задания, обхода и источника.
     */
    private record Payload(Long taskId, Long crawlRunId, Long sourceId) {
    }
}
