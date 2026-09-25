package com.roleorienta.worker.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.worker.adapters.FetchedPosting;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.jobs.TypedJobHandler;
import com.roleorienta.worker.scheduling.CrawlTaskRepository;
import com.roleorienta.worker.scheduling.SourceRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Обработчик задания {@code FETCH_POSTING} — второе звено конвейера сбора (§6 техдока).
 *
 * <p>Оркестрирует сбор детали: находит источник и уже существующую публикацию (её
 * создал {@code DISCOVER_PAGE}), дозапрашивает деталь через адаптер и передаёт её в
 * {@link PostingEnricher} (нормализация полей, извлечение языков, история изменений),
 * после чего сохраняет публикацию и отмечает задание выполненным. Само доменное
 * обогащение вынесено в {@link PostingEnricher}, чтобы обработчик оставался тонким.</p>
 *
 * <p>Границы транзакции — как у {@code DiscoverPageJobHandler}: HTTP-вызов внутри
 * транзакции слушателя (оговорка пилота, Этап 1). Повторный GET и запись через
 * сущность идемпотентны.</p>
 */
@Component
public class FetchPostingJobHandler implements TypedJobHandler {

    private static final Logger log = LoggerFactory.getLogger(FetchPostingJobHandler.class);

    private final SourceRepository sourceRepository;
    private final CrawlTaskRepository crawlTaskRepository;
    private final JobPostingRepository jobPostingRepository;
    private final SourceAdapterRegistry adapterRegistry;
    private final PostingEnricher postingEnricher;

    /** Разбор тела задания (JSON). Создаётся локально (как в {@code SourceScheduler}). */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param sourceRepository     источники
     * @param crawlTaskRepository  задания
     * @param jobPostingRepository публикации
     * @param adapterRegistry      реестр адаптеров источников
     * @param postingEnricher      обогащение публикации детальными данными
     */
    public FetchPostingJobHandler(
            SourceRepository sourceRepository,
            CrawlTaskRepository crawlTaskRepository,
            JobPostingRepository jobPostingRepository,
            SourceAdapterRegistry adapterRegistry,
            PostingEnricher postingEnricher) {
        this.sourceRepository = sourceRepository;
        this.crawlTaskRepository = crawlTaskRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.adapterRegistry = adapterRegistry;
        this.postingEnricher = postingEnricher;
    }

    @Override
    public String taskType() {
        return CrawlTaskType.FETCH_POSTING.name();
    }

    @Override
    public void handle(JobMessage message) {
        Payload payload = parse(message.payload());

        Source source = sourceRepository.findById(payload.sourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Источник не найден: id=" + payload.sourceId()));
        if (source.getState() != SourceState.ACTIVE) {
            // Источник поставлен на паузу/отключён после постановки задания (§60): задание
            // снимается без запроса к источнику — иначе очередь дорабатывала бы остановленный сбор.
            markTask(payload.taskId(), CrawlTaskState.FAILED);
            log.info("FETCH_POSTING: источник {} в состоянии {} — задание снято без запроса",
                    source.getId(), source.getState());
            return;
        }

        SourceAdapter adapter = adapterRegistry.forProviderCode(source.getProvider().getCode());
        FetchedPosting detail = adapter.getPosting(source, payload.externalId());

        JobPosting posting = jobPostingRepository
                .findBySource_IdAndExternalId(source.getId(), payload.externalId())
                .orElseThrow(() -> new IllegalStateException(
                        "Публикация для детали не найдена: source=" + source.getId()
                                + " externalId=" + payload.externalId()));

        postingEnricher.enrich(posting, detail, Instant.now());
        jobPostingRepository.save(posting);

        markTask(payload.taskId(), CrawlTaskState.SUCCEEDED);

        log.info("FETCH_POSTING: источник {} ({}), публикация {} — деталь обработана",
                source.getId(), source.getProvider().getCode(), payload.externalId());
    }

    private void markTask(Long taskId, CrawlTaskState state) {
        CrawlTask task = crawlTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Задание не найдено: id=" + taskId));
        task.setState(state);
        crawlTaskRepository.save(task);
    }

    /**
     * Разбирает тело задания, сформированное {@code DiscoverPageJobHandler}.
     */
    private Payload parse(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return new Payload(
                    node.path("taskId").asLong(),
                    node.path("sourceId").asLong(),
                    node.path("externalId").asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось разобрать тело задания FETCH_POSTING", exception);
        }
    }

    /**
     * Разобранное тело задания: идентификаторы задания и источника и внешний ID публикации.
     */
    private record Payload(Long taskId, Long sourceId, String externalId) {
    }
}
