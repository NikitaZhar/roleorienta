package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.stateportal.PortalEmployer;
import com.roleorienta.worker.adapter.stateportal.PortalSite;
import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.adapter.stateportal.StatePortalProperties;
import com.roleorienta.worker.stateportal.StatePortalRepository.DueEmployer;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code STATE_PORTAL}: работодатели государственного портала → источник их вакансий
 * (технический документ §5.1; бизнес-описание §4.1, «один канал на компанию»). Два шага, каждый —
 * цепочкой заданий ограниченного размера (задание укладывается в аренду):
 *
 * <ol>
 *   <li>{@code list} — список работодателей портала, по {@link #LIST_PAGES_PER_TASK} страниц
 *       (по 200 работодателей) за задание, записывается в {@code portal_employer}; раз в неделю.</li>
 *   <li>{@code check} — по {@link StatePortalProperties#checksPerTask()} работодателей, которые есть в
 *       реестре, не агентства и без своего источника: есть ли у них вакансии на портале (вместе с
 *       объявлениями площадок, привязанными к IČO). Есть — подключается источник портала. Каждый день,
 *       пока есть работодатели к проверке; повтор — через {@link StatePortalProperties#recheckAfter()}.</li>
 *   <li>{@code site} — у компаний с подключённым порталом и без сайта: сайт из детали их вакансии на
 *       портале («Internetová adresa», иначе домен контактной почты) записывается сайтом компании; его
 *       проверяет поиск кадровой страницы (своя кадровая страница важнее портала). Каждый день.</li>
 * </ol>
 *
 * <p>Отказ страницы списка — повтор задания с того же места; отказ проверки одного работодателя — он
 * остаётся непроверенным и берётся следующим заданием; портал не ответил ни разу за задание — повтор
 * задания позже (цепочка не продолжается вхолостую).</p>
 */
@Component
public class StatePortalHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "STATE_PORTAL";

    /** Страниц списка работодателей за задание (≈ 1 с на страницу). */
    static final int LIST_PAGES_PER_TASK = 20;

    private static final String LIST = "list";
    private static final String CHECK = "check";
    private static final String SITE = "site";
    private static final String SLOVAKIA = "SK";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(StatePortalHandler.class);

    private final StatePortalAdapter adapter;
    private final StatePortalRepository repository;
    private final TaskService taskService;
    private final StatePortalProperties properties;

    /**
     * @param adapter     адаптер портала
     * @param repository  работодатели портала и подключение
     * @param taskService постановка следующего задания цепочки
     * @param properties  работодателей за задание, срок повторной проверки
     */
    public StatePortalHandler(StatePortalAdapter adapter, StatePortalRepository repository,
            TaskService taskService, StatePortalProperties properties) {
        this.adapter = adapter;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * Ставит шаг «список» с первой страницы.
     *
     * @param period часть ключа: неделя (список читается раз в неделю)
     * @return поставлено ли (повтор в ту же неделю ничего не добавляет)
     */
    public boolean enqueueList(String period) {
        return taskService.enqueue(TYPE, key(LIST, period, 1), payload(LIST, period, 1));
    }

    /**
     * Ставит шаг «проверка».
     *
     * @param period часть ключа: день (проверка идёт каждый день)
     * @return поставлено ли (повтор в тот же день ничего не добавляет)
     */
    public boolean enqueueCheck(String period) {
        return taskService.enqueue(TYPE, key(CHECK, period, 1), payload(CHECK, period, 1));
    }

    /**
     * Ставит шаг «сайт».
     *
     * @param period часть ключа: день
     * @return поставлено ли (повтор в тот же день ничего не добавляет)
     */
    public boolean enqueueSite(String period) {
        return taskService.enqueue(TYPE, key(SITE, period, 1), payload(SITE, period, 1));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = parse(task.payload());
        String period = payload.path("period").asText();
        int position = payload.path("position").asInt(1);
        return switch (payload.path("step").asText()) {
            case LIST -> list(period, position);
            case SITE -> site(period, position);
            default -> check(period, position);
        };
    }

    /**
     * Страницы списка работодателей с {@code firstPage}; список не кончился — следующее задание.
     */
    private TaskOutcome list(String period, int firstPage) {
        for (int page = firstPage; page < firstPage + LIST_PAGES_PER_TASK; page++) {
            Optional<List<PortalEmployer>> employers = adapter.employers(page);
            if (employers.isEmpty()) {
                if (page > firstPage) {
                    next(LIST, period, page);
                    return new TaskOutcome.Done();
                }
                return new TaskOutcome.Retry("State portal employer list page " + page + " not read", Duration.ZERO);
            }
            if (employers.get().isEmpty()) {
                LOG.info("State portal employer list read: {} pages", page - 1);
                return new TaskOutcome.Done();
            }
            repository.saveListed(employers.get());
        }
        next(LIST, period, firstPage + LIST_PAGES_PER_TASK);
        return new TaskOutcome.Done();
    }

    /**
     * Проверка работодателей; взято полное число — следующее задание (номер в ключе растёт).
     */
    private TaskOutcome check(String period, int round) {
        List<DueEmployer> due = repository.employersToCheck(properties.checksPerTask(), properties.recheckAfter());
        int checked = 0;
        int connected = 0;
        for (DueEmployer employer : due) {
            Optional<Boolean> hasOffers = adapter.hasPostingsIn(employer.registrationNumber(), SLOVAKIA);
            if (hasOffers.isEmpty()) {
                LOG.warn("State portal employer {} not checked", employer.registrationNumber());
                continue;
            }
            repository.recordCheck(employer, hasOffers.get());
            checked++;
            connected += hasOffers.get() ? 1 : 0;
        }
        LOG.info("State portal check: {} of {} employers checked, {} with offers", checked, due.size(), connected);
        if (checked == 0 && !due.isEmpty()) {
            return new TaskOutcome.Retry("State portal not answering", Duration.ZERO);
        }
        if (due.size() == properties.checksPerTask()) {
            next(CHECK, period, round + 1);
        }
        return new TaskOutcome.Done();
    }

    /**
     * Поиск сайтов; взято полное число — следующее задание.
     */
    private TaskOutcome site(String period, int round) {
        List<DueEmployer> due = repository.employersForSite(properties.checksPerTask(), properties.recheckAfter());
        int read = 0;
        int found = 0;
        for (DueEmployer employer : due) {
            Optional<PortalSite> site = adapter.employerSite(employer.registrationNumber());
            if (site.isEmpty()) {
                LOG.warn("State portal employer {}: site not read", employer.registrationNumber());
                continue;
            }
            repository.recordSite(employer, site.get());
            read++;
            found += site.get().host() == null ? 0 : 1;
        }
        LOG.info("State portal sites: {} of {} employers read, {} sites found", read, due.size(), found);
        if (read == 0 && !due.isEmpty()) {
            return new TaskOutcome.Retry("State portal not answering", Duration.ZERO);
        }
        if (due.size() == properties.checksPerTask()) {
            next(SITE, period, round + 1);
        }
        return new TaskOutcome.Done();
    }

    private void next(String step, String period, int position) {
        taskService.enqueue(TYPE, key(step, period, position), payload(step, period, position));
    }

    private static String key(String step, String period, int position) {
        return "state-portal:" + step + ":" + period + ":" + position;
    }

    private static String payload(String step, String period, int position) {
        return JSON.createObjectNode().put("step", step).put("period", period).put("position", position).toString();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed STATE_PORTAL payload: " + payload, exception);
        }
    }
}
