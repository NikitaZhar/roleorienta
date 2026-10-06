package com.roleorienta.worker.site;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.site.SiteNameRepository.DueCompany;
import com.roleorienta.worker.site.SiteNameRepository.NameSite;
import com.roleorienta.worker.site.SiteVerifier.Verdict;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code SITE_NAME} — шаги 3–4 алгоритма поиска сайта версии 3 (технический документ §5.1): адреса из
 * названия компании ({@link SiteBrand#addresses}) проверяются по порядку ({@link SiteVerifier}):
 *
 * <ol>
 *   <li>IČO на странице — находка {@code REGISTRATION_NUMBER} (шаг 3; дальше не проверяется);</li>
 *   <li>иначе первый адрес, подтверждённый брендом, — находка {@code BRAND} (шаг 4);</li>
 *   <li>иначе сайт группы ({@code GROUP_SITE}): находка, если тот же домен дал другой источник, иначе кандидат;
 *       ответ 403 по адресу {@code www.бренд.sk} / {@code www.бренд.com} — кандидат {@code HTTP_403}.</li>
 * </ol>
 *
 * <p>Компании — с признаком найма или с 10+ сотрудниками (RÚZ), по {@link SiteNameProperties#companiesPerTask()} за задание; взято полное
 * число — следующее задание цепочки. Задание идёт в свою очередь: проверка одной компании — до 36 адресов.
 * Временный отказ адреса пропускается: компания перепроверяется через срок.</p>
 */
@Component
public class SiteNameHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "SITE_NAME";

    private static final String PROOF_NUMBER = "REGISTRATION_NUMBER";
    private static final String PROOF_BRAND = "BRAND";
    private static final String PROOF_GROUP = "GROUP_SITE";
    private static final String PROOF_403 = "HTTP_403";
    private static final String WWW = "www.";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(SiteNameHandler.class);

    private final SiteNameRepository repository;
    private final SiteVerifier verifier;
    private final TaskService taskService;
    private final SiteNameProperties properties;

    /**
     * @param repository  компании к проверке и запись итога
     * @param verifier    проверка адреса
     * @param taskService постановка следующего задания цепочки
     * @param properties  компаний за задание, срок перепроверки
     */
    public SiteNameHandler(SiteNameRepository repository, SiteVerifier verifier, TaskService taskService,
            SiteNameProperties properties) {
        this.repository = repository;
        this.verifier = verifier;
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * Ставит первое задание цепочки.
     *
     * @param period часть ключа: день (шаг идёт раз в сутки)
     * @return поставлено ли (повтор в тот же день ничего не добавляет)
     */
    public boolean enqueue(String period) {
        return taskService.enqueue(TYPE, key(period, 1), payload(period, 1));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = parse(task.payload());
        String period = payload.path("period").asText();
        int round = payload.path("round").asInt(1);
        List<DueCompany> due = repository.dueCompanies(properties.companiesPerTask(), properties.recheckAfter(),
                properties.minEmployees());
        int found = 0;
        for (DueCompany company : due) {
            List<NameSite> sites = check(company);
            repository.record(company.id(), sites);
            found += sites.stream().anyMatch(NameSite::found) ? 1 : 0;
        }
        LOG.info("Sites by name: {} companies checked, {} sites found", due.size(), found);
        if (due.size() == properties.companiesPerTask()) {
            taskService.enqueue(TYPE, key(period, round + 1), payload(period, round + 1));
        }
        return new TaskOutcome.Done();
    }

    /**
     * Адреса компании по порядку.
     *
     * @return находка (одна) или кандидаты; пусто — ничего
     */
    private List<NameSite> check(DueCompany company) {
        NameSite brand = null;
        NameSite group = null;
        List<NameSite> candidates = new ArrayList<>();
        for (SiteAddress address : SiteBrand.addresses(company.name())) {
            Verdict verdict = verifier.verify(address.host(), address.path(), company.registrationNumber(),
                    company.name());
            switch (verdict) {
                case REGISTRATION_NUMBER -> {
                    return List.of(site(address, PROOF_NUMBER, true));
                }
                case BRAND -> brand = brand == null ? site(address, PROOF_BRAND, true) : brand;
                case GROUP -> {
                    String domain = address.host().startsWith(WWW) ? address.host().substring(WWW.length())
                            : address.host();
                    NameSite site = site(address, PROOF_GROUP, repository.hasDomain(company.id(), domain));
                    group = group == null && site.found() ? site : group;
                    candidates.add(site);
                }
                case CLOSED -> {
                    if (SiteBrand.brandHome(address.host(), company.name())) {
                        candidates.add(site(address, PROOF_403, false));
                    }
                }
                case OPENED, GONE, TEMPORARY -> {
                    // подтверждения нет — адрес не записывается
                }
            }
        }
        if (brand != null) {
            return List.of(brand);
        }
        return group != null ? List.of(group) : candidates;
    }

    private NameSite site(SiteAddress address, String proof, boolean found) {
        String url = verifier.start(address.host(), address.path()).toString();
        return new NameSite(address.host(), address.path().isEmpty() ? null : url, url, proof, found);
    }

    private static String key(String period, int round) {
        return "site-name:" + period + ":" + round;
    }

    private static String payload(String period, int round) {
        return JSON.createObjectNode().put("period", period).put("round", round).toString();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed SITE_NAME payload: " + payload, exception);
        }
    }
}
