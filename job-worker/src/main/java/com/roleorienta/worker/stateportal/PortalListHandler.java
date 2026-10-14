package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.stateportal.PortalEmployer;
import com.roleorienta.worker.adapter.stateportal.PortalOffer;
import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.adapter.stateportal.StatePortalSearch;
import com.roleorienta.worker.adapter.stateportal.StatePortalSearch.OfferPage;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.time.Duration;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code PORTAL_LIST}: общий список вакансий государственного портала в {@code portal_offer} (технический
 * документ §15, п. 2, срез 4; стенограмма §104, §105). Два вида заданий:
 *
 * <ol>
 *   <li>{@code cycle} — полный обход: страницы списка «старые сверху» (изменённая вакансия уходит в конец списка и не
 *       пропускается), по {@link PortalListProperties#pagesPerTask()} за задание — квота в час, продолжение в следующем
 *       часу. В ответе портала записей меньше полной страницы — обход завершён ({@code portal_cycle.completed_at}).</li>
 *   <li>{@code feed} — лента «новые сверху»: первая страница раз в час; номер обхода у вакансий не меняет.</li>
 * </ol>
 *
 * <p>IČO вакансии (в списке его нет) определяется один раз и хранится у вакансии: по названию работодателя из списка
 * работодателей портала (только однозначно), иначе поиском работодателей портала по названию (итог хранится по
 * названию; без результата — снова через {@link PortalListProperties#nameRecheckAfter()}), иначе — у своей вакансии
 * портала — со страницы вакансии. Не определён — вакансия записывается без IČO, следующий обход пробует снова.</p>
 *
 * <p>Срок задания ({@link PortalListProperties#timeBudget()}) ограничивает и поиск IČO: вышел — найденное записывается,
 * страница дочитывается в следующем часу. Отказ первой страницы задания — повтор задания; отказ следующей — продолжение
 * с неё в следующем часу.</p>
 *
 * <p>В источники портала (§107): завершённый обход — подключение новых работодателей и полное чтение каждого источника
 * портала; лента — неполное чтение работодателей с новыми вакансиями ({@link PortalRecordHandler}).</p>
 */
@Component
public class PortalListHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PORTAL_LIST";

    private static final String CYCLE = "cycle";
    private static final String FEED = "feed";
    /** Начало ключей цепочки полного обхода. */
    private static final String CYCLE_PREFIX = "portal-list:cycle:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(PortalListHandler.class);

    private final StatePortalSearch search;
    private final PortalListRepository repository;
    private final SourceRepository sources;
    private final TaskService taskService;
    private final PortalListProperties properties;

    /**
     * @param search      общий список портала и поиск работодателей
     * @param repository  вакансии, обходы, IČO по названию, подключение работодателей
     * @param sources     источники портала к записи (правило «один канал на компанию»)
     * @param taskService постановка заданий
     * @param properties  размер страницы и задания, срок между обходами
     */
    public PortalListHandler(StatePortalSearch search, PortalListRepository repository, SourceRepository sources,
            TaskService taskService, PortalListProperties properties) {
        this.search = search;
        this.repository = repository;
        this.sources = sources;
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * Начинает полный обход, если цепочка обхода не идёт и пора ({@link PortalListRepository#cycleDue}).
     *
     * @return начат ли обход
     */
    public boolean startCycleIfDue() {
        if (taskService.hasActiveChain(TYPE, CYCLE_PREFIX) || !repository.cycleDue(properties.cycleEvery())) {
            return false;
        }
        long cycle = repository.startCycle();
        return taskService.enqueue(TYPE, cycleKey(cycle, 1, 0), cyclePayload(cycle, 1, 0));
    }

    /**
     * Ставит ленту «новые сверху».
     *
     * @param hour часть ключа: час (повтор в тот же час ничего не добавляет)
     * @return поставлено ли
     */
    public boolean enqueueFeed(String hour) {
        return taskService.enqueue(TYPE, "portal-list:feed:" + hour,
                JSON.createObjectNode().put("mode", FEED).put("hour", hour).toString());
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = parse(task.payload());
        long deadline = System.nanoTime() + properties.timeBudget().toNanos();
        if (FEED.equals(payload.path("mode").asText())) {
            Optional<OfferPage> page = search.offers(1, properties.pageSize(), true);
            if (page.isEmpty()) {
                return new TaskOutcome.Retry("State portal list not read", Duration.ZERO);
            }
            List<PortalOffer> offers = page.get().offers();
            Set<String> fresh = repository.newExternalIds(offers.stream().map(PortalOffer::externalId).toList());
            save(offers, null, deadline, 0);
            recordNewOffers(fresh, payload.path("hour").asText());
            return new TaskOutcome.Done();
        }
        return cycle(payload.path("cycle").asLong(), payload.path("page").asInt(1), payload.path("from").asInt(0),
                deadline);
    }

    /**
     * Страницы полного обхода с {@code firstPage}: не больше {@link PortalListProperties#pagesPerTask()} и не дольше
     * {@link PortalListProperties#timeBudget()} — срок проверяется и перед каждым поиском IČO (§106): не успели
     * определить IČO всех вакансий страницы — та же страница дочитывается в следующем часу с вакансии, на которой
     * остановились ({@code from}; и часть ключа — продолжение той же страницы не совпадает с ключом текущего задания,
     * а каждое продолжение начинает дальше прошлого — страница продвигается). Обход завершён, когда в ответе портала
     * записей меньше полной страницы — по числу записей ответа, а не годных вакансий (§106).
     */
    private TaskOutcome cycle(long cycle, int firstPage, int from, long deadline) {
        for (int page = firstPage; page < firstPage + properties.pagesPerTask(); page++) {
            if (page > firstPage && System.nanoTime() - deadline > 0) {
                return next(cycle, page, 0);
            }
            Optional<OfferPage> found = search.offers(page, properties.pageSize(), false);
            if (found.isEmpty()) {
                if (page == firstPage) {
                    return new TaskOutcome.Retry("State portal list page " + page + " not read", Duration.ZERO);
                }
                return next(cycle, page, 0);
            }
            List<PortalOffer> offers = found.get().offers();
            int stopped = save(offers, cycle, deadline, page == firstPage ? from : 0);
            if (stopped < offers.size()) {
                return next(cycle, page, stopped);
            }
            if (found.get().records() < properties.pageSize()) {
                recordCycle(cycle);
                repository.completeCycle(cycle);
                LOG.info("State portal list cycle {} completed: {} pages", cycle, page);
                return new TaskOutcome.Done();
            }
        }
        return next(cycle, firstPage + properties.pagesPerTask(), 0);
    }

    /**
     * Завершённый обход: работодателям с вакансиями без источника подключается источник портала, затем каждому
     * источнику портала к чтению (один канал на компанию) — полное чтение из общего списка ({@link PortalRecordHandler}).
     * Вызывается до отметки «обход завершён»: сбой здесь оставляет обход незавершённым, и следующий тик начинает новый,
     * а не теряет полное чтение завершённого (ключи заданий не дают повтора).
     */
    private void recordCycle(long cycle) {
        List<PortalListRepository.NewEmployer> employers = repository.employersToConnect(cycle);
        employers.forEach(repository::connect);
        int recorded = 0;
        for (Source source : portalSources()) {
            recorded += taskService.enqueue(PortalRecordHandler.TYPE, recordKeyPrefix(source) + "c" + cycle,
                    PortalRecordHandler.payload(source.getId(), cycle, true)) ? 1 : 0;
        }
        LOG.info("State portal list cycle {}: {} employers connected, {} sources to record", cycle, employers.size(),
                recorded);
    }

    /**
     * Лента: работодателям с новыми вакансиями (с IČO, источник портала к чтению есть) — неполное чтение из общего
     * списка: новые вакансии видны в течение часа. Источник, у которого запись ещё в очереди или выполняется (полное
     * чтение после обхода), пропускается: записи одного источника не идут параллельно (§94, §95), а новые вакансии
     * войдут в его запись — она берёт всё, что видели с начала обхода.
     */
    private void recordNewOffers(Set<String> fresh, String hour) {
        Optional<Long> cycle = repository.lastCycle();
        Set<String> boards = new HashSet<>(repository.knownNumbers(List.copyOf(fresh)).values());
        if (cycle.isEmpty() || boards.isEmpty()) {
            return;
        }
        Set<String> pending = new HashSet<>(taskService.activeKeys(PortalRecordHandler.TYPE).stream()
                .map(key -> key.substring(0, key.lastIndexOf(':') + 1)).toList());
        for (Source source : portalSources()) {
            if (boards.contains(source.getBoard()) && !pending.contains(recordKeyPrefix(source))) {
                taskService.enqueue(PortalRecordHandler.TYPE, recordKeyPrefix(source) + "f" + hour,
                        PortalRecordHandler.payload(source.getId(), cycle.get(), false));
            }
        }
    }

    /**
     * Источники портала к чтению: правило «один канал на компанию» — как у чтения источников
     * ({@link SourceRepository#findToRead}, срок с прошлого чтения не учитывается).
     */
    private List<Source> portalSources() {
        return sources.findToRead(StatePortalAdapter.PROVIDER, 0).stream()
                .filter(source -> StatePortalAdapter.PROVIDER.equals(source.getProvider())).toList();
    }

    private static String recordKeyPrefix(Source source) {
        return "portal-record:" + source.getId() + ":";
    }

    private TaskOutcome next(long cycle, int page, int from) {
        taskService.enqueueNextHour(TYPE, cycleKey(cycle, page, from), cyclePayload(cycle, page, from));
        return new TaskOutcome.Done();
    }

    /**
     * Вакансии страницы с IČO: известные — из БД, у остальных с позиции {@code from} — {@link #number(PortalOffer)},
     * пока не вышел срок задания (первый поиск — всегда: каждое продолжение страницы начинает дальше прошлого).
     * Записываются все вакансии страницы; IČO не затирается пустым.
     *
     * @return позиция первой вакансии, IČO которой не искали из-за срока; размер страницы — искали у всех
     */
    private int save(List<PortalOffer> offers, Long cycle, long deadline, int from) {
        Map<String, String> numbers = repository.knownNumbers(offers.stream().map(PortalOffer::externalId).toList());
        boolean searched = false;
        int stopped = offers.size();
        int unresolved = 0;
        for (int index = from; index < offers.size(); index++) {
            PortalOffer offer = offers.get(index);
            if (numbers.containsKey(offer.externalId())) {
                continue;
            }
            if (searched && System.nanoTime() - deadline > 0) {
                stopped = index;
                break;
            }
            searched = true;
            Optional<String> number = number(offer);
            number.ifPresent(found -> numbers.put(offer.externalId(), found));
            unresolved += number.isEmpty() ? 1 : 0;
        }
        repository.saveOffers(offers, numbers, cycle);
        if (unresolved > 0) {
            LOG.info("State portal list: {} of {} offers without registration number", unresolved, offers.size());
        }
        return stopped;
    }

    /**
     * IČO вакансии: список работодателей портала → поиск работодателей по названию → страница своей вакансии.
     */
    private Optional<String> number(PortalOffer offer) {
        String name = offer.employerName();
        Optional<String> known = repository.listedNumber(name).or(() -> repository.searchedNumber(name));
        if (known.isPresent()) {
            return known;
        }
        if (!repository.searchedRecently(name, properties.nameRecheckAfter())) {
            Optional<List<PortalEmployer>> found = search.employersNamed(name);
            if (found.isPresent()) {
                List<String> exact = found.get().stream().filter(employer -> name.equals(employer.name()))
                        .map(PortalEmployer::registrationNumber).distinct().toList();
                String number = exact.size() == 1 ? exact.get(0) : null;
                repository.saveSearched(name, number);
                if (number != null) {
                    return Optional.of(number);
                }
            }
        }
        return offer.own() ? search.offerNumber(offer.externalId()) : Optional.empty();
    }

    private static String cycleKey(long cycle, int page, int from) {
        return CYCLE_PREFIX + cycle + ":" + page + (from == 0 ? "" : "." + from);
    }

    private static String cyclePayload(long cycle, int page, int from) {
        return JSON.createObjectNode().put("mode", CYCLE).put("cycle", cycle).put("page", page).put("from", from)
                .toString();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed PORTAL_LIST payload: " + payload, exception);
        }
    }
}
