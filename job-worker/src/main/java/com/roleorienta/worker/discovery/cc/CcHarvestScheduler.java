package com.roleorienta.worker.discovery.cc;

import com.roleorienta.worker.discovery.cc.HarvestStore.Cursor;
import com.roleorienta.worker.http.SourceBackoffException;
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
 * Автоматический вход обнаружения по индексу Common Crawl (§55, A1b) — заменяет ручной seed
 * как основной поток кандидатов (§14, ADR-17). Входы — {@link CcInput}: Workday (§55), Personio
 * (§91); у каждого свой курсор и свой бюджет страниц за проход.
 *
 * <p>Проход ({@link #runOnce()}) — две независимые фазы:</p>
 * <ol>
 *   <li><b>Сбор</b> ({@link #collect()}): под арендой {@link HarvestStore#tryLease}, <b>вне
 *       транзакции</b>, читает до {@code pagesPerPass} страниц индекса начиная с курсора,
 *       разбирает URL в доски ({@link CcInput#board}, дедуп по ключу провайдера)
 *       и фиксирует каждую страницу вместе с продвижением курсора
 *       ({@link HarvestStore#recordPage}). Появилась новая коллекция индекса или изменён
 *       {@code page-size} — обход начинается со страницы 0 (уже известные доски гасятся
 *       дедупом). Коллекция
 *       обойдена до конца — сеть не трогается до следующей коллекции (кроме одного
 *       запроса {@code collinfo.json}).</li>
 *   <li><b>Fan-out</b> ({@link BoardFanOut}): под leader-lock (ключ {@value BoardFanOut#FANOUT_LOCK_KEY},
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
 * <p><b>Сбор не опережает проверку (§73).</b> Страница индекса даёт ~150 досок, а fan-out
 * за проход — {@code maxFanOut} (20): если читать страницу каждый проход, очередь {@code NEW}
 * растёт без предела (стенд §72: 655 после 5 проходов). Поэтому новая страница читается,
 * только когда в очереди меньше {@code maxFanOut} досок — иначе проход только передаёт
 * накопленное.</p>
 *
 * <p><b>Включение.</b> Тик ({@code CcHarvestTrigger}) включён по умолчанию
 * ({@code app.discovery.cc.enabled}, §72); в тестах выключен. Проход можно вызвать напрямую.</p>
 */
@Component
public class CcHarvestScheduler {

    /** Код входа — ключ строки {@code harvest_cursor} и метка досок. */
    private static final Logger log = LoggerFactory.getLogger(CcHarvestScheduler.class);

    private final CcHarvestProperties properties;
    private final CommonCrawlIndexClient indexClient;
    private final HarvestStore store;
    private final BoardFanOut fanOut;

    /**
     * @param properties  шаблон индекса, размер страницы, бюджеты прохода
     * @param indexClient клиент индекса Common Crawl
     * @param store       накопитель досок и курсор обхода
     * @param fanOut      фаза передачи досок в контур обнаружения
     */
    public CcHarvestScheduler(CcHarvestProperties properties, CommonCrawlIndexClient indexClient,
                              HarvestStore store, BoardFanOut fanOut) {
        this.properties = properties;
        this.indexClient = indexClient;
        this.store = store;
        this.fanOut = fanOut;
    }

    /**
     * Один проход: сбор, затем fan-out. Ошибка сбора логируется и не отменяет fan-out;
     * ошибка fan-out поднимается вызывающему (тик повторит).
     */
    public void runOnce() {
        try {
            collect();
        } catch (CommonCrawlIndexClient.IncompleteIndexPageException | HttpServerErrorException
                | SourceBackoffException unstable) {
            // Штатная нестабильность общего CDX-сервера (§55.5a): обрыв ответа, 502/503.
            // Страница не засчитана, курсор на месте — без стектрейса, чтобы не шуметь в логе.
            log.warn("CC-гарвест: индекс временно недоступен, повтор на следующем тике: {}", unstable.getMessage());
        } catch (RuntimeException exception) {
            log.warn("CC-гарвест: сбор из индекса не удался, повтор на следующем тике", exception);
        }
        fanOut.run();
    }

    /**
     * Фаза сбора под арендой.
     *
     * @return сколько досок добавлено впервые (0 — очередь ещё не разобрана, аренда занята,
     *         обход завершён или пусто)
     */
    /**
     * Проход по всем включённым входам ({@code app.discovery.cc.inputs}); сбой одного входа
     * прерывает проход (штатная нестабильность индекса общая для всех входов).
     *
     * @return сколько досок добавлено впервые
     */
    int collect() {
        int added = 0;
        for (CcInput input : properties.inputs()) {
            added += collect(input);
        }
        return added;
    }

    private int collect(CcInput input) {
        int backlog = store.countNewBoards(input.code());
        if (backlog >= properties.maxFanOut()) {
            log.info("CC-гарвест {}: в очереди {} досок (≥ {} за проход) — новая страница индекса не читается",
                    input.code(), backlog, properties.maxFanOut());
            return 0;
        }
        Optional<Cursor> lease = store.tryLease(input.code(), properties.leaseSeconds());
        if (lease.isEmpty()) {
            log.debug("CC-гарвест {}: проход уже идёт в другой реплике", input.code());
            return 0;
        }
        try {
            return collectUnderLease(input, lease.get());
        } finally {
            store.releaseLease(input.code());
        }
    }

    private int collectUnderLease(CcInput input, Cursor cursor) {
        String collection = indexClient.latestCollection();
        int pageSize = properties.pageSize();
        int pageCount = cursor.pageCount();
        int page = cursor.nextPage();
        if (!collection.equals(cursor.collection()) || pageSize != cursor.pageSize()) {
            // Новая коллекция или другой размер страницы — номера страниц курсора недействительны:
            // обход с начала, уже известные доски гасятся дедупом накопителя.
            pageCount = indexClient.pageCount(collection, input.urlPattern(), pageSize);
            page = 0;
            store.recordPosition(input.code(), new Cursor(collection, pageSize, pageCount, page));
            log.info("CC-гарвест {}: коллекция {} (pageSize {}) — {} страниц, обход с начала",
                    input.code(), collection, pageSize, pageCount);
        }
        int added = 0;
        int fetched = 0;
        while (fetched < properties.pagesPerPass() && page < pageCount) {
            List<String> urls = indexClient.urlsOnPage(collection, input.urlPattern(), pageSize, page);
            Collection<HarvestedBoard> boards = boardsOf(input, urls);
            page++;
            fetched++;
            int newBoards = store.recordPage(input.code(), input.providerCode(),
                    new Cursor(collection, pageSize, pageCount, page), boards);
            added += newBoards;
            log.info("CC-гарвест {}: {} стр. {}/{} — URL {}, досок {}, новых {}",
                    input.code(), collection, page, pageCount, urls.size(), boards.size(), newBoards);
        }
        return added;
    }

    /**
     * Доски страницы индекса без повторов (ключ дедупа провайдера), в порядке первого появления.
     *
     * @param input вход
     * @param urls  адреса страницы
     * @return доски
     */
    static Collection<HarvestedBoard> boardsOf(CcInput input, List<String> urls) {
        Map<String, HarvestedBoard> byKey = new LinkedHashMap<>();
        for (String url : urls) {
            input.board(url).ifPresent(board -> byKey.putIfAbsent(board.dedupKey(), board));
        }
        return byKey.values();
    }
}
