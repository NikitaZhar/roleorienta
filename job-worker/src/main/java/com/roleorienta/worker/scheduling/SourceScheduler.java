package com.roleorienta.worker.scheduling;

import com.roleorienta.core.domain.CrawlRunState;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Планировщик обхода источников (§6 техдока, ADR-12).
 *
 * <p>Под leader-lock (чтобы при нескольких репликах планировала только одна) для
 * каждого активного источника создаёт в текущем окне расписания обход
 * ({@code crawl_run}), задание ({@code crawl_task}) и outbox-событие ({@link DiscoverPageEnqueuer}) — всё в
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
    private final DiscoverPageEnqueuer enqueuer;
    private final PostgresLeaderLock leaderLock;
    private final long windowSizeMs;

    /**
     * @param sourceRepository   источники
     * @param crawlRunRepository обходы
     * @param enqueuer           постановка задания {@code DISCOVER_PAGE} и события
     * @param leaderLock         leader-lock роли планировщика
     * @param properties         настройки планировщика (размер окна расписания)
     */
    public SourceScheduler(SourceRepository sourceRepository, CrawlRunRepository crawlRunRepository,
                           DiscoverPageEnqueuer enqueuer, PostgresLeaderLock leaderLock,
                           SchedulerProperties properties) {
        this.sourceRepository = sourceRepository;
        this.crawlRunRepository = crawlRunRepository;
        this.enqueuer = enqueuer;
        this.leaderLock = leaderLock;
        this.windowSizeMs = properties.windowMs();
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
                enqueuer.enqueue(crawlRunRepository.getReferenceById(runId), source.getId(), windowStart);
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
}
