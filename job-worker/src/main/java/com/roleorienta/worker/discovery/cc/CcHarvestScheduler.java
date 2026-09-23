package com.roleorienta.worker.discovery.cc;

import com.roleorienta.worker.adapters.workday.WorkdayAdapter;
import com.roleorienta.worker.adapters.workday.WorkdayBoard;
import com.roleorienta.worker.discovery.DiscoverEmployerPayload;
import com.roleorienta.worker.discovery.EmployerCandidateRepository;
import com.roleorienta.worker.discovery.cc.HarvestStore.BoardState;
import com.roleorienta.worker.discovery.cc.HarvestStore.Cursor;
import com.roleorienta.worker.discovery.cc.HarvestStore.PendingBoard;
import com.roleorienta.worker.lock.PostgresLeaderLock;
import com.roleorienta.worker.outbox.OutboxEventRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Автоматический вход обнаружения Workday по индексу Common Crawl (§55, A1b) — заменяет
 * ручной seed как основной поток кандидатов (§14, ADR-17).
 *
 * <p>Проход ({@link #runOnce()}) — две независимые фазы:</p>
 * <ol>
 *   <li><b>Сбор</b> ({@link #collect()}): под арендой {@link HarvestStore#tryLease}, <b>вне
 *       транзакции</b>, читает до {@code pagesPerPass} страниц индекса начиная с курсора,
 *       разбирает URL в доски ({@link WorkdayBoard#fromCareerUrl}, дедуп без учёта регистра)
 *       и фиксирует каждую страницу вместе с продвижением курсора
 *       ({@link HarvestStore#recordPage}). Появилась новая коллекция индекса или изменён
 *       {@code page-size} — обход начинается со страницы 0 (уже известные доски гасятся
 *       дедупом). Коллекция
 *       обойдена до конца — сеть не трогается до следующей коллекции (кроме одного
 *       запроса {@code collinfo.json}).</li>
 *   <li><b>Fan-out</b> ({@link #fanOut()}): под leader-lock (ключ {@value #FANOUT_LOCK_KEY},
 *       только БД, короткая транзакция) берёт до {@code maxFanOut} досок {@code NEW}; если
 *       кандидат с такой доской уже есть (без учёта регистра) — {@code SKIPPED}, иначе
 *       ставит {@code DISCOVER_EMPLOYER} через outbox — {@code ENQUEUED}. Дальше —
 *       обычный контур (§35): проверка ленты, гейт уверенности.</li>
 * </ol>
 *
 * <p>Фазы развязаны накопителем: одна страница индекса даёт сотни досок, а fan-out
 * ограничен бюджетом — доски не теряются и не ставятся дважды. Сбой сбора (503 индекса,
 * тайм-аут) не мешает fan-out уже накопленного.</p>
 *
 * <p><b>Включение.</b> Тик ({@code CcHarvestTrigger}) по умолчанию выключен
 * ({@code app.discovery.cc.enabled=false}): до бюджета/rate-limit источников (B1) и потолка
 * тела ответа (B2) живой обход не включается. Проход можно вызвать напрямую.</p>
 */
@Component
public class CcHarvestScheduler {

    /** Код входа — ключ строки {@code harvest_cursor} и метка досок. */
    static final String INPUT_CODE = "cc-workday";

    /** Ключ advisory-лока fan-out (реестр ключей: 1001 источники, 1002 seed, 1003 матчер — D4). */
    static final long FANOUT_LOCK_KEY = 1004L;

    private static final Logger log = LoggerFactory.getLogger(CcHarvestScheduler.class);

    private final CcHarvestProperties properties;
    private final CommonCrawlIndexClient indexClient;
    private final HarvestStore store;
    private final EmployerCandidateRepository candidateRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostgresLeaderLock leaderLock;

    public CcHarvestScheduler(
            CcHarvestProperties properties,
            CommonCrawlIndexClient indexClient,
            HarvestStore store,
            EmployerCandidateRepository candidateRepository,
            OutboxEventRepository outboxEventRepository,
            PostgresLeaderLock leaderLock) {
        this.properties = properties;
        this.indexClient = indexClient;
        this.store = store;
        this.candidateRepository = candidateRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.leaderLock = leaderLock;
    }

    /**
     * Один проход: сбор, затем fan-out. Ошибка сбора логируется и не отменяет fan-out;
     * ошибка fan-out поднимается вызывающему (тик повторит).
     */
    public void runOnce() {
        try {
            collect();
        } catch (CommonCrawlIndexClient.IncompleteIndexPageException | HttpServerErrorException unstable) {
            // Штатная нестабильность общего CDX-сервера (§55.5a): обрыв ответа, 502/503.
            // Страница не засчитана, курсор на месте — без стектрейса, чтобы не шуметь в логе.
            log.warn("CC-гарвест: индекс временно недоступен, повтор на следующем тике: {}", unstable.getMessage());
        } catch (RuntimeException e) {
            log.warn("CC-гарвест: сбор из индекса не удался, повтор на следующем тике", e);
        }
        fanOut();
    }

    /**
     * Фаза сбора под арендой.
     *
     * @return сколько досок добавлено впервые (0 — аренда занята, обход завершён или пусто)
     */
    int collect() {
        Optional<Cursor> lease = store.tryLease(INPUT_CODE, properties.leaseSeconds());
        if (lease.isEmpty()) {
            log.debug("CC-гарвест: проход уже идёт в другой реплике");
            return 0;
        }
        try {
            return collectUnderLease(lease.get());
        } finally {
            store.releaseLease(INPUT_CODE);
        }
    }

    private int collectUnderLease(Cursor cursor) {
        String collection = indexClient.latestCollection();
        int pageSize = properties.pageSize();
        int pageCount = cursor.pageCount();
        int page = cursor.nextPage();
        if (!collection.equals(cursor.collection()) || pageSize != cursor.pageSize()) {
            // Новая коллекция или другой размер страницы — номера страниц курсора недействительны:
            // обход с начала, уже известные доски гасятся дедупом накопителя.
            pageCount = indexClient.pageCount(collection, properties.urlPattern(), pageSize);
            page = 0;
            store.recordPosition(INPUT_CODE, new Cursor(collection, pageSize, pageCount, page));
            log.info("CC-гарвест: коллекция {} (pageSize {}) — {} страниц, обход с начала",
                    collection, pageSize, pageCount);
        }
        int added = 0;
        int fetched = 0;
        while (fetched < properties.pagesPerPass() && page < pageCount) {
            List<String> urls = indexClient.urlsOnPage(collection, properties.urlPattern(), pageSize, page);
            Collection<WorkdayBoard> boards = boardsOf(urls);
            page++;
            fetched++;
            int newBoards = store.recordPage(INPUT_CODE, WorkdayAdapter.PROVIDER_CODE,
                    new Cursor(collection, pageSize, pageCount, page), boards);
            added += newBoards;
            log.info("CC-гарвест: {} стр. {}/{} — URL {}, досок {}, новых {}",
                    collection, page, pageCount, urls.size(), boards.size(), newBoards);
        }
        return added;
    }

    /** Доски из URL страницы: разбор и дедуп без учёта регистра (первое написание). */
    static Collection<WorkdayBoard> boardsOf(List<String> urls) {
        Map<String, WorkdayBoard> byKey = new LinkedHashMap<>();
        for (String url : urls) {
            WorkdayBoard.fromCareerUrl(url).ifPresent(b -> byKey.putIfAbsent(b.dedupKey(), b));
        }
        return byKey.values();
    }

    /**
     * Фаза fan-out под leader-lock.
     *
     * @return {@code true}, если эта реплика была лидером и выполнила фазу
     */
    boolean fanOut() {
        return leaderLock.runIfLeader(FANOUT_LOCK_KEY, this::fanOutBatch);
    }

    /** Одна пачка fan-out; выполняется в транзакции leader-lock. */
    void fanOutBatch() {
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
