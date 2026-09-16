package com.roleorienta.worker.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlRun;
import com.roleorienta.core.domain.CrawlRunState;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.jobs.TypedJobHandler;
import com.roleorienta.worker.scheduling.CrawlRunRepository;
import com.roleorienta.worker.scheduling.CrawlTaskRepository;
import com.roleorienta.worker.scheduling.SourceRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Обработчик задания {@code DISCOVER_PAGE} — первый шаг конвейера сбора (§6 техдока).
 *
 * <p>Читает ленту источника через адаптер, сохраняет обнаруженные публикации
 * (идемпотентный upsert) и отмечает обход и задание завершёнными. Публикации из
 * ленты уже несут заголовок и ссылку, поэтому отдельной перекачки деталей на этом
 * шаге нет; получение полей с detail-endpoint — это задание {@code FETCH_POSTING}
 * следующего инкремента.</p>
 *
 * <p><b>Границы транзакции (пилот, Этап 1).</b> Метод вызывается внутри транзакции
 * слушателя (общей с фиксацией ключа идемпотентности), поэтому HTTP-вызов адаптера
 * происходит в этой же транзакции. Разнесение HTTP и записи в БД по разным
 * транзакциям (§6, шаг 4, «HTTP-вызов не удерживает длительную транзакцию БД») —
 * улучшение Этапа 2: оно затрагивает транзакцию идемпотентности слушателя
 * (магистраль доставки) и вынесено отдельной задачей, чтобы не расширять текущую
 * область. Для пилота против заглушки-источника с ограниченным ответом это
 * приемлемо; повторный GET при повторе задания безопасен (чтение идемпотентно).</p>
 */
@Component
public class DiscoverPageJobHandler implements TypedJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DiscoverPageJobHandler.class);

    private final SourceRepository sourceRepository;
    private final CrawlRunRepository crawlRunRepository;
    private final CrawlTaskRepository crawlTaskRepository;
    private final JobPostingRepository jobPostingRepository;
    private final SourceAdapterRegistry adapterRegistry;

    /** Разбор тела задания (JSON). Создаётся локально (как в {@code SourceScheduler}). */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param sourceRepository     источники
     * @param crawlRunRepository   обходы
     * @param crawlTaskRepository  задания
     * @param jobPostingRepository публикации
     * @param adapterRegistry      реестр адаптеров источников
     */
    public DiscoverPageJobHandler(
            SourceRepository sourceRepository,
            CrawlRunRepository crawlRunRepository,
            CrawlTaskRepository crawlTaskRepository,
            JobPostingRepository jobPostingRepository,
            SourceAdapterRegistry adapterRegistry) {
        this.sourceRepository = sourceRepository;
        this.crawlRunRepository = crawlRunRepository;
        this.crawlTaskRepository = crawlTaskRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.adapterRegistry = adapterRegistry;
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

        SourceAdapter adapter = adapterRegistry.forProviderCode(source.getProvider().getCode());
        PostingsPage page = adapter.listPostings(source, null);

        Instant now = Instant.now();
        for (DiscoveredPosting posting : page.postings()) {
            jobPostingRepository.upsert(
                    source.getId(),
                    posting.externalId(),
                    posting.url(),
                    posting.rawTitle(),
                    now);
        }

        markCompleted(payload.crawlRunId(), payload.taskId());

        log.info("DISCOVER_PAGE: источник {} ({}), обнаружено публикаций {}",
                source.getId(), source.getProvider().getCode(), page.postings().size());
    }

    /**
     * Отмечает обход и задание успешно завершёнными.
     */
    private void markCompleted(Long crawlRunId, Long taskId) {
        CrawlRun run = crawlRunRepository.findById(crawlRunId)
                .orElseThrow(() -> new IllegalStateException("Обход не найден: id=" + crawlRunId));
        run.setState(CrawlRunState.COMPLETED);
        crawlRunRepository.save(run);

        CrawlTask task = crawlTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Задание не найдено: id=" + taskId));
        task.setState(CrawlTaskState.SUCCEEDED);
        crawlTaskRepository.save(task);
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
