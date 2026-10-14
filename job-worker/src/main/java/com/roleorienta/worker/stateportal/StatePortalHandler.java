package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.stateportal.PortalEmployer;
import com.roleorienta.worker.adapter.stateportal.PortalSite;
import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.adapter.stateportal.StatePortalProperties;
import com.roleorienta.worker.site.SiteVerifier;
import com.roleorienta.worker.site.SiteVerifier.Verdict;
import com.roleorienta.worker.stateportal.StatePortalRepository.DueEmployer;
import com.roleorienta.worker.stateportal.StatePortalRepository.FoundSite;
import com.roleorienta.worker.stateportal.StatePortalRepository.SiteEmployer;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *   <li>{@code check} — по {@link StatePortalProperties.Task#checksPerTask()} работодателей, которые есть в
 *       реестре, не агентства и без своего источника: есть ли у них вакансии на портале (вместе с
 *       объявлениями площадок, привязанными к IČO). Есть — подключается источник портала. Одно задание в час
 *       (квота, §101), пока есть работодатели к проверке; повтор — через {@link StatePortalProperties#recheckAfter()}.</li>
 *   <li>{@code site} — у работодателей портала, которые есть в реестре, действуют, из активной страны сбора, в том
 *       числе с уже найденным сайтом (§93; §74: без условия о подключённом портале; шаг 2 алгоритма поиска
 *       сайта версии 3): сайт из детали их вакансии на портале («Internetová adresa» полным адресом, иначе
 *       домен контактной почты с проверкой сайта) записывается сайтом компании; его проверяет поиск
 *       кадровой страницы (своя кадровая страница важнее портала). Одно задание в час (квота, §101).</li>
 * </ol>
 *
 * <p>Отказ страницы списка — повтор задания с того же места; отказ по одному работодателю, когда остальные
 * прочитаны, — он отмечается проверенным и берётся снова через срок перепроверки (иначе такие работодатели
 * копятся в начале очереди и останавливают цепочку); портал не ответил ни разу за задание — повтор задания
 * позже (цепочка не продолжается вхолостую).</p>
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
    /** Начало ключей заданий; шаг — следующая часть ключа (цепочка шага — ключи «state-portal:<шаг>:»). */
    private static final String KEY_PREFIX = "state-portal:";
    private static final String STATE_PORTAL = "STATE_PORTAL";
    private static final String PORTAL_MAIL = "PORTAL_MAIL";
    private static final String PORTAL_MAIL_403 = "PORTAL_MAIL_403";
    private static final String GROUP_SITE = "GROUP_SITE";
    private static final String WWW = "www.";
    private static final String SLOVAKIA = "SK";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(StatePortalHandler.class);

    private final StatePortalAdapter adapter;
    private final StatePortalRepository repository;
    private final TaskService taskService;
    private final StatePortalProperties properties;
    private final SiteVerifier verifier;

    /**
     * @param adapter     адаптер портала
     * @param repository  работодатели портала и подключение
     * @param taskService постановка следующего задания цепочки
     * @param properties  работодателей за задание, срок повторной проверки
     * @param verifier    проверка сайта по домену почты (IČO, бренд)
     */
    public StatePortalHandler(StatePortalAdapter adapter, StatePortalRepository repository,
            TaskService taskService, StatePortalProperties properties, SiteVerifier verifier) {
        this.adapter = adapter;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
        this.verifier = verifier;
    }

    /**
     * Ставит шаг «список» с первой страницы.
     *
     * @param period часть ключа: неделя (список читается раз в неделю)
     * @return поставлено ли (цепочка шага идёт или повтор в ту же неделю — ничего не добавляется)
     */
    public boolean enqueueList(String period) {
        return startStep(LIST, period);
    }

    /**
     * Ставит шаг «проверка».
     *
     * @param period часть ключа: день (проверка идёт каждый день)
     * @return поставлено ли (цепочка шага идёт или повтор в тот же день — ничего не добавляется)
     */
    public boolean enqueueCheck(String period) {
        return startStep(CHECK, period);
    }

    /**
     * Ставит шаг «сайт».
     *
     * @param period часть ключа: день
     * @return поставлено ли (цепочка шага идёт или повтор в тот же день — ничего не добавляется)
     */
    public boolean enqueueSite(String period) {
        return startStep(SITE, period);
    }

    /**
     * Первое задание шага — только если цепочка этого шага не идёт (стенограмма §95): иначе цепочки за разные дни
     * шли параллельно по одним и тем же работодателям (§94).
     */
    private boolean startStep(String step, String period) {
        return taskService.startChain(TYPE, KEY_PREFIX + step + ":", key(step, period, 1), payload(step, period, 1));
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
     * Проверка работодателей; взято полное число или вышел срок задания — следующее задание (номер в ключе растёт).
     * Не начатые из-за срока работодатели остаются к проверке; первый работодатель проверяется всегда — цепочка
     * продвигается и при сроке меньше одного запроса (§100).
     */
    private TaskOutcome check(String period, int round) {
        List<DueEmployer> due = repository.employersToCheck(properties.task().checksPerTask(),
                properties.recheckAfter());
        long deadline = System.nanoTime() + properties.task().timeBudget().toNanos();
        boolean outOfTime = false;
        int checked = 0;
        int connected = 0;
        List<String> failed = new ArrayList<>();
        for (DueEmployer employer : due) {
            if (checked + failed.size() > 0 && System.nanoTime() - deadline > 0) {
                outOfTime = true;
                break;
            }
            Optional<Boolean> hasOffers = adapter.hasPostingsIn(employer.registrationNumber(), SLOVAKIA);
            if (hasOffers.isEmpty()) {
                LOG.warn("State portal employer {} not checked", employer.registrationNumber());
                failed.add(employer.registrationNumber());
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
        failed.forEach(repository::markChecked);
        if (outOfTime || due.size() == properties.task().checksPerTask()) {
            nextHour(CHECK, period, round + 1);
        }
        return new TaskOutcome.Done();
    }

    /**
     * Поиск сайтов; взято полное число или вышел срок задания — следующее задание. Первый работодатель — всегда.
     */
    private TaskOutcome site(String period, int round) {
        List<SiteEmployer> due = repository.employersForSite(properties.task().checksPerTask(),
                properties.recheckAfter());
        long deadline = System.nanoTime() + properties.task().timeBudget().toNanos();
        boolean outOfTime = false;
        int read = 0;
        int found = 0;
        List<SiteEmployer> failed = new ArrayList<>();
        for (SiteEmployer employer : due) {
            if (read + failed.size() > 0 && System.nanoTime() - deadline > 0) {
                outOfTime = true;
                break;
            }
            Optional<PortalSite> site = adapter.employerSite(employer.registrationNumber());
            if (site.isEmpty()) {
                LOG.warn("State portal employer {}: site not read", employer.registrationNumber());
                failed.add(employer);
                continue;
            }
            read++;
            found += recordSite(employer, site.get()) ? 1 : 0;
        }
        LOG.info("State portal sites: {} of {} employers read, {} sites found", read, due.size(), found);
        if (read == 0 && !due.isEmpty()) {
            return new TaskOutcome.Retry("State portal not answering", Duration.ZERO);
        }
        failed.forEach(employer -> repository.recordSite(employer, null));
        if (outOfTime || due.size() == properties.task().checksPerTask()) {
            nextHour(SITE, period, round + 1);
        }
        return new TaskOutcome.Done();
    }

    /**
     * Итог шага 2 алгоритма версии 3 (технический документ §5.1) по ответу портала: «Internetová adresa» —
     * {@code STATE_PORTAL} без проверки (адрес указал сам работодатель рядом со своим IČO; адрес с путём —
     * стартовая страница); домен почты — проверка сайта {@code www.<домен>}: IČO или бренд —
     * {@code PORTAL_MAIL}, ответ 401/403 — {@code PORTAL_MAIL_403} (запрет robots.txt — сайта нет, как в скрипте
     * замера; стенограмма §73), открылся без подтверждения —
     * {@code GROUP_SITE} (почта на домене самого работодателя), не существует — сайта нет. Временный отказ
     * сайта — сайт не записывается, работодатель проверяется снова через срок перепроверки.
     *
     * @return записан ли найденный сайт
     */
    private boolean recordSite(SiteEmployer employer, PortalSite site) {
        FoundSite found = null;
        if (site.website() != null) {
            URI uri = URI.create(site.website());
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            boolean home = (path.isEmpty() || "/".equals(path)) && uri.getRawQuery() == null;
            found = new FoundSite(uri.getHost().toLowerCase(Locale.ROOT), home ? null : site.website(),
                    site.evidenceUrl(), STATE_PORTAL);
        } else if (site.mailHost() != null) {
            String host = site.mailHost().startsWith(WWW) ? site.mailHost() : WWW + site.mailHost();
            Verdict verdict = verifier.verify(host, "", employer.registrationNumber(), employer.name());
            if (verdict == Verdict.TEMPORARY) {
                LOG.info("State portal employer {}: mail site {} not answering, checked after recheck period",
                        employer.registrationNumber(), host);
                repository.recordSite(employer, null);
                return false;
            }
            String proof = switch (verdict) {
                case REGISTRATION_NUMBER, BRAND -> PORTAL_MAIL;
                case CLOSED -> PORTAL_MAIL_403;
                case GROUP, OPENED -> GROUP_SITE;
                case ROBOTS, GONE, TEMPORARY -> null;
            };
            found = proof == null ? null : new FoundSite(host, null, site.evidenceUrl(), proof);
        }
        repository.recordSite(employer, found);
        return found != null;
    }

    private void next(String step, String period, int position) {
        taskService.enqueue(TYPE, key(step, period, position), payload(step, period, position));
    }

    /** Продолжение проверки и поиска сайта — в следующем часу: квота в час — размер задания (§101). */
    private void nextHour(String step, String period, int position) {
        taskService.enqueueNextHour(TYPE, key(step, period, position), payload(step, period, position));
    }

    private static String key(String step, String period, int position) {
        return KEY_PREFIX + step + ":" + period + ":" + position;
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
