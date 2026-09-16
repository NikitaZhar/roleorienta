package com.roleorienta.worker.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.core.domain.Source;
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
 * <p>Дозапрашивает детальную страницу одной публикации через адаптер и сохраняет
 * добранные поля (локация, зарплата), которых нет в ленте-списке. Публикация уже
 * создана заданием {@code DISCOVER_PAGE}, поэтому детали дописываются к существующей
 * строке (UPDATE по {@code (source_id, external_id)}). Поля хранятся сырыми;
 * нормализация — отдельный срез (§6).</p>
 *
 * <p>Границы транзакции — те же, что у {@code DiscoverPageJobHandler}: HTTP-вызов
 * внутри транзакции слушателя (оговорка пилота, Этап 1). Повторный GET при повторе
 * задания безопасен (чтение идемпотентно), запись детали идемпотентна (UPDATE).</p>
 */
@Component
public class FetchPostingJobHandler implements TypedJobHandler {

    private static final Logger log = LoggerFactory.getLogger(FetchPostingJobHandler.class);

    private final SourceRepository sourceRepository;
    private final CrawlTaskRepository crawlTaskRepository;
    private final JobPostingRepository jobPostingRepository;
    private final SourceAdapterRegistry adapterRegistry;

    /** Разбор тела задания (JSON). Создаётся локально (как в {@code SourceScheduler}). */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param sourceRepository     источники
     * @param crawlTaskRepository  задания
     * @param jobPostingRepository публикации
     * @param adapterRegistry      реестр адаптеров источников
     */
    public FetchPostingJobHandler(
            SourceRepository sourceRepository,
            CrawlTaskRepository crawlTaskRepository,
            JobPostingRepository jobPostingRepository,
            SourceAdapterRegistry adapterRegistry) {
        this.sourceRepository = sourceRepository;
        this.crawlTaskRepository = crawlTaskRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.adapterRegistry = adapterRegistry;
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

        SourceAdapter adapter = adapterRegistry.forProviderCode(source.getProvider().getCode());
        FetchedPosting detail = adapter.getPosting(source, payload.externalId());

        int updated = jobPostingRepository.updateDetails(
                source.getId(),
                payload.externalId(),
                detail.rawLocation(),
                detail.rawCompensation(),
                Instant.now());
        if (updated == 0) {
            throw new IllegalStateException(
                    "Публикация для детали не найдена: source=" + source.getId()
                            + " externalId=" + payload.externalId());
        }

        CrawlTask task = crawlTaskRepository.findById(payload.taskId())
                .orElseThrow(() -> new IllegalStateException("Задание не найдено: id=" + payload.taskId()));
        task.setState(CrawlTaskState.SUCCEEDED);
        crawlTaskRepository.save(task);

        log.info("FETCH_POSTING: источник {} ({}), публикация {}, локация={}, зарплата={}",
                source.getId(), source.getProvider().getCode(), payload.externalId(),
                detail.rawLocation(), detail.rawCompensation());
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать тело задания FETCH_POSTING", e);
        }
    }

    /**
     * Разобранное тело задания: идентификаторы задания и источника и внешний ID публикации.
     */
    private record Payload(Long taskId, Long sourceId, String externalId) {
    }
}
