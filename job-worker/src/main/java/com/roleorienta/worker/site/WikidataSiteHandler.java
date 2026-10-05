package com.roleorienta.worker.site;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.site.WikidataSiteRepository.DueCompany;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code SITE_WIKIDATA} — шаг 1 алгоритма поиска сайта (технический документ §5.1): официальные
 * сайты из Wikidata по IČO для компаний без найденного сайта. Задание читает все пары «IČO → сайт»
 * ({@link WikidataClient}), берёт до {@link WikidataProperties#sitesPerTask()} компаний к проверке и
 * открывает их сайты:
 *
 * <ul>
 *   <li>открылся (в том числе тело больше потолка) — находка {@code WIKIDATA};</li>
 *   <li>закрыт для программы (401/403 или robots.txt) — находка {@code WIKIDATA_403};</li>
 *   <li>не существует, удалён или адрес недопустим — кандидат {@code WIKIDATA};</li>
 *   <li>временный отказ — компания остаётся к проверке следующим заданием.</li>
 * </ul>
 *
 * <p>Взято полное число компаний — следующее задание цепочки. Нет разрешения на использование Wikidata —
 * задание ничего не делает. Wikidata не ответила — повтор позже.</p>
 */
@Component
public class WikidataSiteHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "SITE_WIKIDATA";

    private static final String OPENED = "WIKIDATA";
    private static final String CLOSED = "WIKIDATA_403";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(WikidataSiteHandler.class);

    private final WikidataClient client;
    private final WikidataSiteRepository repository;
    private final ExternalHttpClient httpClient;
    private final TaskService taskService;
    private final WikidataProperties properties;

    /**
     * @param client      пары «IČO → сайт» из Wikidata
     * @param repository  компании к проверке и запись находки
     * @param httpClient  проверка, открывается ли сайт
     * @param taskService постановка следующего задания цепочки
     * @param properties  компаний за задание
     */
    public WikidataSiteHandler(WikidataClient client, WikidataSiteRepository repository,
            ExternalHttpClient httpClient, TaskService taskService, WikidataProperties properties) {
        this.client = client;
        this.repository = repository;
        this.httpClient = httpClient;
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
        if (!repository.permitted()) {
            LOG.info("Wikidata is not permitted in source_permission, site step skipped");
            return new TaskOutcome.Done();
        }
        Optional<List<WikidataSite>> sites = client.sites();
        if (sites.isEmpty()) {
            return new TaskOutcome.Retry("Wikidata not answering", Duration.ZERO);
        }
        Map<String, WikidataSite> byNumber = new LinkedHashMap<>();
        sites.get().forEach(site -> byNumber.putIfAbsent(site.registrationNumber(), site));
        List<DueCompany> due = repository.dueCompanies(byNumber.keySet(), properties.sitesPerTask());
        int recorded = 0;
        for (DueCompany company : due) {
            recorded += check(company, byNumber.get(company.registrationNumber())) ? 1 : 0;
        }
        LOG.info("Wikidata sites: {} pairs, {} companies to check, {} recorded", byNumber.size(), due.size(),
                recorded);
        if (due.size() == properties.sitesPerTask()) {
            taskService.enqueue(TYPE, key(period, round + 1), payload(period, round + 1));
        }
        return new TaskOutcome.Done();
    }

    /**
     * Открывает сайт и записывает итог; временный отказ — не записывается.
     *
     * @return записан ли итог
     */
    private boolean check(DueCompany company, WikidataSite site) {
        URI uri;
        try {
            uri = URI.create(site.url().strip());
        } catch (IllegalArgumentException malformed) {
            LOG.info("Wikidata site of company {} is not an address: {}", company.id(), site.url());
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            LOG.info("Wikidata site of company {} has no host: {}", company.id(), site.url());
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        switch (httpClient.get(uri)) {
            case HttpResult.Success success -> repository.record(company.id(), host, site.item(), OPENED, true);
            case HttpResult.PermanentFailure failure -> {
                switch (failure.kind()) {
                    case TOO_LARGE -> repository.record(company.id(), host, site.item(), OPENED, true);
                    case ACCESS_DENIED, USE_FORBIDDEN -> repository.record(company.id(), host, site.item(), CLOSED,
                            true);
                    default -> repository.record(company.id(), host, site.item(), OPENED, false);
                }
            }
            case HttpResult.TemporaryFailure temporary -> {
                return false;
            }
        }
        return true;
    }

    private static String key(String period, int round) {
        return "site-wikidata:" + period + ":" + round;
    }

    private static String payload(String period, int round) {
        return JSON.createObjectNode().put("period", period).put("round", round).toString();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed SITE_WIKIDATA payload: " + payload, exception);
        }
    }
}
