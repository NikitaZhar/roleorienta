package com.roleorienta.worker.scheduling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlRun;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.worker.outbox.OutboxEvent;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ставит первое задание обхода — {@code DISCOVER_PAGE}: задание ({@code crawl_task}) и
 * outbox-событие в транзакции вызывающего. Вынесено из {@link SourceScheduler} (§78): иначе у
 * него 6 зависимостей (контракт §3.10).
 */
@Component
public class DiscoverPageEnqueuer {

    private final CrawlTaskRepository crawlTaskRepository;
    private final OutboxEventRepository outboxEventRepository;

    /** Формирование тел заданий (JSON). Создаётся локально, потокобезопасен. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param crawlTaskRepository   задания
     * @param outboxEventRepository outbox
     */
    public DiscoverPageEnqueuer(CrawlTaskRepository crawlTaskRepository,
                                OutboxEventRepository outboxEventRepository) {
        this.crawlTaskRepository = crawlTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Создаёт задание {@code DISCOVER_PAGE} в обходе и событие для магистрали.
     *
     * @param run         обход (достаточно ссылки на сущность)
     * @param sourceId    источник
     * @param windowStart начало окна расписания (попадает в тело задания)
     */
    public void enqueue(CrawlRun run, Long sourceId, Instant windowStart) {
        CrawlTask task = crawlTaskRepository.save(
                new CrawlTask(run, CrawlTaskType.DISCOVER_PAGE, CrawlTaskState.SCHEDULED));
        outboxEventRepository.save(new OutboxEvent(
                "CrawlTask",
                String.valueOf(task.getId()),
                CrawlTaskType.DISCOVER_PAGE.name(),
                payload(task.getId(), run.getId(), sourceId, windowStart),
                null));
    }

    /** JSON-тело события задания. */
    private String payload(Long taskId, Long crawlRunId, Long sourceId, Instant windowStart) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("crawlRunId", crawlRunId);
        body.put("sourceId", sourceId);
        body.put("type", CrawlTaskType.DISCOVER_PAGE.name());
        body.put("windowStart", windowStart.toString());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сформировать payload задания", exception);
        }
    }
}
