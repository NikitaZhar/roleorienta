package com.roleorienta.worker.scheduling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlRunState;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.outbox.OutboxEvent;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Планировщик обхода источников (§6 техдока, ADR-12).
 *
 * <p>Под leader-lock (чтобы при нескольких репликах планировала только одна) для
 * каждого активного источника создаёт в текущем окне расписания обход
 * ({@code crawl_run}), задание ({@code crawl_task}) и outbox-событие — всё в
 * одной транзакции. Идемпотентность обеспечивает уникальный ключ
 * {@code (source, окно)}: повторный тик в том же окне не создаёт дублей. Событие
 * далее доставляется существующей магистралью (публикатор → очередь → потребитель).</p>
 *
 * <p>Окно расписания — квант времени фиксированного размера
 * ({@code app.scheduler.window-ms}): {@code windowStart} — момент, усечённый вниз
 * до кратного размеру окна. Разбор cron-выражения из {@code Source.schedule} —
 * упрощение, оставленное на следующий инкремент (пока все активные источники
 * планируются раз в окно).</p>
 */
@Component
public class SourceScheduler {

    /** Ключ advisory-лока роли планировщика (согласованное число, ADR-12). */
    private static final long SCHEDULER_LOCK_KEY = 1001L;

    private static final Logger log = LoggerFactory.getLogger(SourceScheduler.class);

    private final SourceRepository sourceRepository;
    private final CrawlRunRepository crawlRunRepository;
    private final CrawlTaskRepository crawlTaskRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostgresLeaderLock leaderLock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long windowSizeMs;

    /**
     * @param sourceRepository      источники
     * @param crawlRunRepository    обходы
     * @param crawlTaskRepository   задания
     * @param outboxEventRepository outbox
     * @param leaderLock            leader-lock роли планировщика
     * @param windowSizeMs          размер окна расписания, мс
     */
    public SourceScheduler(
            SourceRepository sourceRepository,
            CrawlRunRepository crawlRunRepository,
            CrawlTaskRepository crawlTaskRepository,
            OutboxEventRepository outboxEventRepository,
            PostgresLeaderLock leaderLock,
            @Value("${app.scheduler.window-ms:900000}") long windowSizeMs) {
        this.sourceRepository = sourceRepository;
        this.crawlRunRepository = crawlRunRepository;
        this.crawlTaskRepository = crawlTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.leaderLock = leaderLock;
        this.windowSizeMs = windowSizeMs;
    }

    /**
     * Один проход планирования под leader-lock. Если лок держит другая реплика —
     * ничего не делает.
     *
     * @return {@code true}, если эта реплика была лидером и выполнила планирование
     */
    public boolean runOnce() {
        return leaderLock.runIfLeader(SCHEDULER_LOCK_KEY, this::scheduleDueSources);
    }

    /**
     * Планирует активные источники в текущем окне. Выполняется в транзакции
     * leader-lock; не помечается транзакционным отдельно.
     */
    private void scheduleDueSources() {
        Instant windowStart = currentWindowStart();
        List<Source> sources = sourceRepository.findByState(SourceState.ACTIVE);
        int scheduled = 0;
        for (Source source : sources) {
            if (crawlRunRepository.insertIfAbsent(source.getId(), windowStart, CrawlRunState.SCHEDULED.name()) == 1) {
                Long runId = crawlRunRepository.findId(source.getId(), windowStart);
                CrawlTask task = crawlTaskRepository.save(new CrawlTask(
                        crawlRunRepository.getReferenceById(runId),
                        CrawlTaskType.DISCOVER_PAGE,
                        CrawlTaskState.SCHEDULED));
                outboxEventRepository.save(new OutboxEvent(
                        "CrawlTask",
                        String.valueOf(task.getId()),
                        CrawlTaskType.DISCOVER_PAGE.name(),
                        payload(task.getId(), runId, source.getId(), windowStart),
                        null));
                scheduled++;
            }
        }
        log.info("Планировщик: окно {}, активных источников {}, создано заданий {}",
                windowStart, sources.size(), scheduled);
    }

    /**
     * Начало текущего окна расписания: момент, усечённый вниз до кратного размеру окна.
     *
     * @return начало окна (UTC)
     */
    private Instant currentWindowStart() {
        long now = Instant.now().toEpochMilli();
        return Instant.ofEpochMilli(now - (now % windowSizeMs));
    }

    /**
     * Формирует JSON-тело события задания.
     */
    private String payload(Long taskId, Long crawlRunId, Long sourceId, Instant windowStart) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("crawlRunId", crawlRunId);
        body.put("sourceId", sourceId);
        body.put("type", CrawlTaskType.DISCOVER_PAGE.name());
        body.put("windowStart", windowStart.toString());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сформировать payload задания", e);
        }
    }
}
