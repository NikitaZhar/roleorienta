package com.roleorienta.worker.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.core.domain.CrawlRun;
import com.roleorienta.core.domain.CrawlTaskType;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.worker.adapters.DiscoveredPosting;
import com.roleorienta.worker.adapters.PostingsPage;
import com.roleorienta.worker.adapters.SourceAdapter;
import com.roleorienta.worker.adapters.SourceAdapterRegistry;
import com.roleorienta.worker.jobs.JobMessage;
import com.roleorienta.worker.jobs.TypedJobHandler;
import com.roleorienta.worker.adapters.MarketScope;
import com.roleorienta.worker.discovery.DiscoveryMarketProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Обработчик задания {@code DISCOVER_PAGE} — первое звено конвейера сбора (§6 техдока).
 *
 * <p>Читает ленту источника через адаптер, идемпотентно сохраняет обнаруженные
 * публикации и для каждой ставит задание {@code FETCH_POSTING} (дозапрос детали) —
 * задание и outbox-событие создаются в той же транзакции, тем же паттерном, что у
 * планировщика. Так первое звено запускает второе через штатную магистраль (§13).
 * В конце отмечает обход и своё задание завершёнными.</p>
 *
 * <p><b>Только рынок и пагинация (§62).</b> Лента читается в области целевого рынка
 * ({@link DiscoveryMarketProperties#toScope()}): адаптер, умеющий фильтровать на стороне
 * провайдера (Workday), отдаёт только публикации SK/AT — у крупного работодателя это
 * единицы-десятки вместо тысяч. Страницы читаются по курсору до его конца, но не больше
 * {@code app.collect.max-list-pages} за обход (бюджет запросов, A29).</p>
 *
 * <p><b>Ниша и бюджет деталей (§63)</b> — {@link PostingIntake}. В ленту сохраняются все публикации рынка, но
 * {@code FETCH_POSTING} (отдельный запрос детали) ставится только для заголовков ниши
 * ({@link NicheFilterProperties}) и не больше дневного бюджета на источник; новые
 * (ещё без детали) — первыми, перечитывание уже известных — на остаток бюджета.</p>
 *
 * <p><b>Границы транзакции (пилот, Этап 1).</b> Метод вызывается внутри транзакции
 * слушателя (общей с фиксацией ключа идемпотентности), поэтому HTTP-вызов адаптера
 * происходит в этой же транзакции. Разнесение HTTP и записи в БД по разным
 * транзакциям (§6, шаг 4) — улучшение Этапа 2, вынесено отдельной задачей.</p>
 */
@Component
public class DiscoverPageJobHandler implements TypedJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DiscoverPageJobHandler.class);

    private final CrawlBookkeeping bookkeeping;
    private final SourceAdapterRegistry adapterRegistry;
    private final MarketScope marketScope;
    private final int maxListPages;
    private final PostingIntake intake;

    /** Разбор тел заданий (JSON). Создаётся локально (как в {@code SourceScheduler}). */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param bookkeeping     источник и обход задания, отметка итога
     * @param adapterRegistry реестр адаптеров источников
     * @param market          целевой рынок (область сбора, §62)
     * @param maxListPages    потолок страниц ленты за один обход
     * @param intake          сохранение публикаций и постановка деталей ниши (§63)
     */
    public DiscoverPageJobHandler(
            CrawlBookkeeping bookkeeping,
            SourceAdapterRegistry adapterRegistry,
            DiscoveryMarketProperties market,
            @Value("${app.collect.max-list-pages:10}") int maxListPages,
            PostingIntake intake) {
        this.bookkeeping = bookkeeping;
        this.adapterRegistry = adapterRegistry;
        this.marketScope = market.toScope();
        this.maxListPages = maxListPages;
        this.intake = intake;
    }

    @Override
    public String taskType() {
        return CrawlTaskType.DISCOVER_PAGE.name();
    }

    @Override
    public void handle(JobMessage message) {
        Payload payload = parse(message.payload());

        Source source = bookkeeping.requireSource(payload.sourceId());
        CrawlRun run = bookkeeping.requireRun(payload.crawlRunId());
        if (source.getState() != SourceState.ACTIVE) {
            // Источник поставлен на паузу/отключён после постановки задания (§60): обход не идёт,
            // FETCH_POSTING не порождаются.
            bookkeeping.finish(run, payload.taskId(), false);
            log.info("DISCOVER_PAGE: источник {} в состоянии {} — обход снят без запроса",
                    source.getId(), source.getState());
            return;
        }

        SourceAdapter adapter = adapterRegistry.forProviderCode(source.getProvider().getCode());
        List<DiscoveredPosting> discovered = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            PostingsPage page = adapter.listPostings(source, cursor, marketScope);
            discovered.addAll(page.postings());
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < maxListPages);
        if (cursor != null) {
            log.info("DISCOVER_PAGE: источник {} — достигнут потолок {} страниц, остаток — в следующем обходе",
                    source.getId(), maxListPages);
        }

        PostingIntake.Intake accepted = intake.accept(run, source.getId(), discovered);
        bookkeeping.finish(run, payload.taskId(), true);

        log.info("DISCOVER_PAGE: источник {} ({}){}, страниц {}, обнаружено публикаций {}, в нише {}, "
                        + "поставлено FETCH_POSTING {}{}",
                source.getId(), source.getProvider().getCode(), marketScope.restricted() ? " [рынок]" : "",
                pages, discovered.size(), accepted.inNiche(), accepted.scheduled(),
                accepted.scheduled() < accepted.inNiche() ? " (остальные — вне дневного бюджета)" : "");
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось разобрать тело задания DISCOVER_PAGE", exception);
        }
    }

    /**
     * Разобранное тело задания: идентификаторы задания, обхода и источника.
     */
    private record Payload(Long taskId, Long crawlRunId, Long sourceId) {
    }
}
