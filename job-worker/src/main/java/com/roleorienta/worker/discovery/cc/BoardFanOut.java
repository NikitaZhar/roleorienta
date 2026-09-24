package com.roleorienta.worker.discovery.cc;

import com.roleorienta.worker.discovery.DiscoverEmployerPayload;
import com.roleorienta.worker.discovery.EmployerCandidateRepository;
import com.roleorienta.worker.discovery.cc.HarvestStore.BoardState;
import com.roleorienta.worker.discovery.cc.HarvestStore.PendingBoard;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Фаза fan-out обхода Common Crawl (§55): под leader-lock берёт до {@code maxFanOut} досок
 * {@code NEW}; если кандидат с такой доской уже есть (без учёта регистра) — {@code SKIPPED},
 * иначе ставит {@code DISCOVER_EMPLOYER} через outbox — {@code ENQUEUED}. Только БД, короткая
 * транзакция. Вынесено из {@link CcHarvestScheduler} (§78): иначе у него 6 зависимостей
 * (контракт §3.10).
 */
@Component
public class BoardFanOut {

    /** Ключ advisory-лока fan-out (реестр ключей: 1001 источники, 1002 seed, 1003 матчер — D4). */
    static final long FANOUT_LOCK_KEY = 1004L;

    private static final Logger log = LoggerFactory.getLogger(BoardFanOut.class);

    private final CcHarvestProperties properties;
    private final HarvestStore store;
    private final EmployerCandidateRepository candidateRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostgresLeaderLock leaderLock;

    /**
     * @param properties            бюджет fan-out ({@code max-fan-out})
     * @param store                 накопитель досок
     * @param candidateRepository   кандидаты (дедуп)
     * @param outboxEventRepository outbox (постановка {@code DISCOVER_EMPLOYER})
     * @param leaderLock            leader-lock фазы
     */
    public BoardFanOut(CcHarvestProperties properties, HarvestStore store,
                       EmployerCandidateRepository candidateRepository,
                       OutboxEventRepository outboxEventRepository, PostgresLeaderLock leaderLock) {
        this.properties = properties;
        this.store = store;
        this.candidateRepository = candidateRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.leaderLock = leaderLock;
    }

    /**
     * Фаза fan-out под leader-lock.
     *
     * @return {@code true}, если эта реплика была лидером и выполнила фазу
     */
    public boolean run() {
        return leaderLock.runIfLeader(FANOUT_LOCK_KEY, this::runBatch);
    }

    /** Одна пачка fan-out; выполняется в транзакции leader-lock. */
    void runBatch() {
        int enqueued = 0;
        int skipped = 0;
        for (PendingBoard board : store.lockNewBoards(properties.maxFanOut())) {
            if (candidateRepository.existsByProviderCodeAndSlugIgnoreCase(board.providerCode(), board.slug())) {
                store.mark(board.id(), BoardState.SKIPPED);
                skipped++;
                continue;
            }
            outboxEventRepository.save(
                    DiscoverEmployerPayload.event(board.providerCode(), board.slug(), board.baseUrl()));
            store.mark(board.id(), BoardState.ENQUEUED);
            enqueued++;
        }
        if (enqueued + skipped > 0) {
            log.info("CC-гарвест: поставлено DISCOVER_EMPLOYER {}, пропущено (кандидат есть) {}", enqueued, skipped);
        }
    }
}
