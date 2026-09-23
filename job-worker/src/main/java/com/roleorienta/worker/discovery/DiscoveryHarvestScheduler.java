package com.roleorienta.worker.discovery;

import com.roleorienta.worker.discovery.DiscoveryHarvestProperties.SeedEntry;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Гарвест-вход обнаружения (§5, §6, ADR-6, §36): периодически подаёт пачку кандидатов
 * в работодатели, чтобы они появлялись без ручного триггера.
 *
 * <p>Под leader-lock (планирует только одна реплика) проходит по курируемому seed и
 * для каждого кандидата, у которого ещё нет записи {@code EmployerCandidate},
 * ставит задание {@code DISCOVER_EMPLOYER} через transactional outbox — тем же
 * механизмом, что и {@code SourceScheduler}. Дальше кандидата обрабатывает
 * {@link DiscoverEmployerJobHandler} (проверка ленты, гейт уверенности, авто-
 * подключение §35).</p>
 *
 * <p><b>Бюджет fan-out (A29).</b> За один проход ставится не более
 * {@code app.discovery.harvest.max-fan-out} заданий — валидный огромный seed не
 * даёт неограниченного размножения заданий. Дедуп: кандидаты с уже существующей
 * записью пропускаются, повторный проход не плодит дубли.</p>
 */
@Component
public class DiscoveryHarvestScheduler {

    /** Ключ advisory-лока роли гарвеста (отдельный от планировщика источников, 1001). */
    static final long HARVEST_LOCK_KEY = 1002L;

    private static final Logger log = LoggerFactory.getLogger(DiscoveryHarvestScheduler.class);

    private final DiscoveryHarvestProperties properties;
    private final EmployerCandidateRepository candidateRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostgresLeaderLock leaderLock;

    public DiscoveryHarvestScheduler(
            DiscoveryHarvestProperties properties,
            EmployerCandidateRepository candidateRepository,
            OutboxEventRepository outboxEventRepository,
            PostgresLeaderLock leaderLock) {
        this.properties = properties;
        this.candidateRepository = candidateRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.leaderLock = leaderLock;
    }

    /**
     * Один проход гарвеста под leader-lock. Если лок держит другая реплика — ничего
     * не делает.
     *
     * @return {@code true}, если эта реплика была лидером и выполнила проход
     */
    public boolean runOnce() {
        return leaderLock.runIfLeader(HARVEST_LOCK_KEY, this::harvest);
    }

    /**
     * Проход по seed: ставит задания на новых кандидатов в пределах бюджета. Выполняется
     * в транзакции leader-lock.
     */
    void harvest() {
        int budget = properties.maxFanOut();
        int enqueued = 0;
        for (SeedEntry entry : properties.seed()) {
            if (enqueued >= budget) {
                break;
            }
            if (candidateRepository.existsByProviderCodeAndSlug(entry.providerCode(), entry.slug())) {
                continue;
            }
            outboxEventRepository.save(
                    DiscoverEmployerPayload.event(entry.providerCode(), entry.slug(), entry.baseUrl()));
            enqueued++;
        }
        log.info("Гарвест обнаружения: seed {}, бюджет {}, поставлено DISCOVER_EMPLOYER {}",
                properties.seed().size(), budget, enqueued);
    }
}
