package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.stateportal.PortalEmployer;
import com.roleorienta.worker.adapter.stateportal.PortalOffer;
import com.roleorienta.worker.adapter.stateportal.StatePortalSearch;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *       часу. Страница короче полной — обход завершён ({@code portal_cycle.completed_at}).</li>
 *   <li>{@code feed} — лента «новые сверху»: первая страница раз в час; номер обхода у вакансий не меняет.</li>
 * </ol>
 *
 * <p>IČO вакансии (в списке его нет) определяется один раз и хранится у вакансии: по названию работодателя из списка
 * работодателей портала (только однозначно), иначе поиском работодателей портала по названию (итог хранится по
 * названию; без результата — снова через {@link PortalListProperties#nameRecheckAfter()}), иначе — у своей вакансии
 * портала — со страницы вакансии. Не определён — вакансия записывается без IČO, следующий обход пробует снова.</p>
 *
 * <p>Отказ первой страницы задания — повтор задания; отказ следующей — продолжение с неё в следующем часу. В
 * источники вакансии пока не пишутся (срез 4б).</p>
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
    private final TaskService taskService;
    private final PortalListProperties properties;

    /**
     * @param search      общий список портала и поиск работодателей
     * @param repository  вакансии, обходы, IČO по названию
     * @param taskService постановка заданий
     * @param properties  размер страницы и задания, срок между обходами
     */
    public PortalListHandler(StatePortalSearch search, PortalListRepository repository, TaskService taskService,
            PortalListProperties properties) {
        this.search = search;
        this.repository = repository;
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
        return taskService.enqueue(TYPE, cycleKey(cycle, 1), cyclePayload(cycle, 1));
    }

    /**
     * Ставит ленту «новые сверху».
     *
     * @param hour часть ключа: час (повтор в тот же час ничего не добавляет)
     * @return поставлено ли
     */
    public boolean enqueueFeed(String hour) {
        return taskService.enqueue(TYPE, "portal-list:feed:" + hour,
                JSON.createObjectNode().put("mode", FEED).toString());
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = parse(task.payload());
        if (FEED.equals(payload.path("mode").asText())) {
            Optional<List<PortalOffer>> offers = search.offers(1, properties.pageSize(), true);
            if (offers.isEmpty()) {
                return new TaskOutcome.Retry("State portal list not read", Duration.ZERO);
            }
            save(offers.get(), null);
            return new TaskOutcome.Done();
        }
        return cycle(payload.path("cycle").asLong(), payload.path("page").asInt(1));
    }

    /**
     * Страницы полного обхода с {@code firstPage}: не больше {@link PortalListProperties#pagesPerTask()} и не дольше
     * {@link PortalListProperties#timeBudget()}; первая страница задания — всегда.
     */
    private TaskOutcome cycle(long cycle, int firstPage) {
        long deadline = System.nanoTime() + properties.timeBudget().toNanos();
        for (int page = firstPage; page < firstPage + properties.pagesPerTask(); page++) {
            if (page > firstPage && System.nanoTime() - deadline > 0) {
                return next(cycle, page);
            }
            Optional<List<PortalOffer>> offers = search.offers(page, properties.pageSize(), false);
            if (offers.isEmpty()) {
                if (page == firstPage) {
                    return new TaskOutcome.Retry("State portal list page " + page + " not read", Duration.ZERO);
                }
                return next(cycle, page);
            }
            save(offers.get(), cycle);
            if (offers.get().size() < properties.pageSize()) {
                repository.completeCycle(cycle);
                LOG.info("State portal list cycle {} completed: {} pages", cycle, page);
                return new TaskOutcome.Done();
            }
        }
        return next(cycle, firstPage + properties.pagesPerTask());
    }

    private TaskOutcome next(long cycle, int page) {
        taskService.enqueueNextHour(TYPE, cycleKey(cycle, page), cyclePayload(cycle, page));
        return new TaskOutcome.Done();
    }

    /**
     * Вакансии страницы с IČO: известные — из БД, остальные — {@link #number(PortalOffer)}.
     */
    private void save(List<PortalOffer> offers, Long cycle) {
        Map<String, String> numbers = repository.knownNumbers(offers.stream().map(PortalOffer::externalId).toList());
        int unresolved = 0;
        for (PortalOffer offer : offers) {
            if (!numbers.containsKey(offer.externalId())) {
                Optional<String> number = number(offer);
                number.ifPresent(found -> numbers.put(offer.externalId(), found));
                unresolved += number.isEmpty() ? 1 : 0;
            }
        }
        repository.saveOffers(offers, numbers, cycle);
        if (unresolved > 0) {
            LOG.info("State portal list: {} of {} offers without registration number", unresolved, offers.size());
        }
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

    private static String cycleKey(long cycle, int page) {
        return CYCLE_PREFIX + cycle + ":" + page;
    }

    private static String cyclePayload(long cycle, int page) {
        return JSON.createObjectNode().put("mode", CYCLE).put("cycle", cycle).put("page", page).toString();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed PORTAL_LIST payload: " + payload, exception);
        }
    }
}
