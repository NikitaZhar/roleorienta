package com.roleorienta.worker.stateportal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.adapter.stateportal.StatePortalProperties;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.stateportal.StatePortalRepository.FoundSite;
import com.roleorienta.worker.stateportal.StatePortalRepository.ProfesiaEmployer;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code PROFESIA_SITE} — шаг «страница компании на profesia.sk» (§82; путь владельца: портал → объявление
 * profesia.sk → страница компании на profesia.sk → кадровая страница компании). У работодателя портала с объявлением
 * profesia.sk открывается объявление, с него — страница компании и её разделы (до трёх); первая ссылка на кадровую
 * страницу или систему найма записывается сайтом компании со стартовой страницей — этой ссылкой, иначе первая ссылка —
 * сайтом компании (подтверждение {@code PROFESIA}: объявление привязано к IČO порталом). Дальше их проверяет поиск
 * кадровой страницы. profesia.sk — только путь к сайту: вакансии с неё не читаются; robots.txt соблюдается HTTP-клиентом
 * (страницы компаний открыты, переадресация отклика {@code redirect.php} — запрещена и не используется).
 *
 * <p>За задание — {@value #EMPLOYERS_PER_TASK} работодателей (до пяти запросов к одному хосту с паузой — задание
 * укладывается в аренду); взято полное число — следующее задание. Работодатель отмечается проверенным при любом
 * исходе и берётся снова через {@link StatePortalProperties#recheckAfter()}.</p>
 */
@Component
public class ProfesiaSiteHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "PROFESIA_SITE";

    private static final int EMPLOYERS_PER_TASK = 20;
    private static final String PROOF = "PROFESIA";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ProfesiaSiteHandler.class);

    private final ExternalHttpClient http;
    private final StatePortalRepository repository;
    private final TaskService taskService;
    private final StatePortalProperties properties;

    /**
     * @param http        внешний HTTP-клиент (robots.txt, пауза на хост)
     * @param repository  работодатели портала и запись сайта
     * @param taskService постановка следующего задания цепочки
     * @param properties  срок повторной проверки
     */
    public ProfesiaSiteHandler(ExternalHttpClient http, StatePortalRepository repository, TaskService taskService,
            StatePortalProperties properties) {
        this.http = http;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * Ставит первое задание цепочки.
     *
     * @param day часть ключа: день
     * @return поставлено ли (повтор в тот же день ничего не добавляет)
     */
    public boolean enqueue(String day) {
        return taskService.enqueue(TYPE, key(day, 1), payload(day, 1));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        JsonNode payload = parse(task.payload());
        String day = payload.path("day").asText();
        int round = payload.path("round").asInt(1);
        List<ProfesiaEmployer> due = repository.employersForProfesia(EMPLOYERS_PER_TASK, properties.recheckAfter());
        int found = 0;
        for (ProfesiaEmployer employer : due) {
            Optional<FoundSite> site = find(employer.adUrl());
            repository.recordProfesia(employer, site.orElse(null));
            found += site.isPresent() ? 1 : 0;
        }
        LOG.info("Profesia company pages: {} employers, {} sites or career pages found", due.size(), found);
        if (due.size() == EMPLOYERS_PER_TASK) {
            taskService.enqueue(TYPE, key(day, round + 1), payload(day, round + 1));
        }
        return new TaskOutcome.Done();
    }

    /**
     * Объявление → страница компании и её разделы → выбранная ссылка компании.
     */
    private Optional<FoundSite> find(String adUrl) {
        Optional<Document> company = page(adUrl).flatMap(ProfesiaCompanyPage::companyPage).flatMap(this::page);
        if (company.isEmpty()) {
            return Optional.empty();
        }
        List<String> links = new ArrayList<>(ProfesiaCompanyPage.companyLinks(company.get()));
        for (String section : ProfesiaCompanyPage.sections(company.get())) {
            page(section).ifPresent(page -> ProfesiaCompanyPage.companyLinks(page).stream()
                    .filter(link -> !links.contains(link)).forEach(links::add));
        }
        return ProfesiaCompanyPage.choose(links).map(link -> site(link, company.get().location()));
    }

    /**
     * Ссылка → сайт: хост ссылки; путь ссылки (кадровая страница) — стартовая страница, главная — без неё.
     */
    private static FoundSite site(String link, String evidenceUrl) {
        URI uri = URI.create(link);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        boolean home = (path.isEmpty() || "/".equals(path)) && uri.getRawQuery() == null;
        return new FoundSite(uri.getHost().toLowerCase(Locale.ROOT), home ? null : link, evidenceUrl, PROOF);
    }

    private Optional<Document> page(String url) {
        URI uri = URI.create(url);
        HttpResult result = http.get(uri);
        return result instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), success.locationOr(uri).toString())) : Optional.empty();
    }

    private static String key(String day, int round) {
        return "profesia-site:" + day + ":" + round;
    }

    private static String payload(String day, int round) {
        return JSON.createObjectNode().put("day", day).put("round", round).toString();
    }

    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed PROFESIA_SITE payload: " + payload, exception);
        }
    }
}
