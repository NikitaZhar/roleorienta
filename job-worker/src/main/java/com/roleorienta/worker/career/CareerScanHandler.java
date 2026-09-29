package com.roleorienta.worker.career;

import com.roleorienta.worker.adapter.jobposting.JobPostingAdapter;
import com.roleorienta.worker.career.CareerScanRepository.CheckResult;
import com.roleorienta.worker.career.CareerScanRepository.Site;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import com.roleorienta.worker.task.TaskService;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code CAREER_SCAN}: сайты компаний → кадровые страницы → источники (технический документ
 * §5.1, подэтап 1.7). Страницы сайтов читаются {@link ExternalHttpClient} (robots.txt, бюджет на
 * хост, защита от SSRF).
 *
 * <ol>
 *   <li>Главная страница: ссылки на доски Workday, Greenhouse, Personio ({@link CareerLinks}).</li>
 *   <li>Досок нет — кадровая страница сайта («kariéra», «práca», «jobs» …): на ней те же ссылки; нет
 *       и их — страница с разметкой {@code JobPosting} (она сама или одна из первых
 *       {@value #VACANCY_CHECKS} ссылок вглубь) подключается как источник {@code jobposting}.</li>
 *   <li>Найденное подключается как источник и связывается с компанией: доска, на которую ссылается
 *       подтверждённый по IČO сайт компании, принадлежит ей (гейт принадлежности, бизнес-описание
 *       §4.2). Итог — с причиной; перепроверка через {@link CareerProperties#recheckAfter()}.</li>
 * </ol>
 *
 * <p>За задание — {@link CareerProperties#sitesPerTask()} сайтов, дальше — следующее задание.
 * Подключённые источники читает общий сбор (раз в сутки).</p>
 */
@Component
public class CareerScanHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "CAREER_SCAN";

    private static final String PAYLOAD = "{}";
    private static final int VACANCY_CHECKS = 3;
    /** Длина {@code source.board} в схеме. */
    private static final int MAX_BOARD = 200;
    private static final Logger LOG = LoggerFactory.getLogger(CareerScanHandler.class);

    private final ExternalHttpClient http;
    private final CareerScanRepository repository;
    private final TaskService taskService;
    private final CareerProperties properties;

    /**
     * @param http        внешний HTTP-клиент
     * @param repository  сайты и источники
     * @param taskService постановка следующего задания
     * @param properties  схема, сайтов за задание, срок перепроверки
     */
    public CareerScanHandler(ExternalHttpClient http, CareerScanRepository repository, TaskService taskService,
            CareerProperties properties) {
        this.http = http;
        this.repository = repository;
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * @param suffix часть ключа: дата постановки или место проверки
     * @return ключ задания
     */
    public static String taskKey(String suffix) {
        return "career-scan:" + suffix;
    }

    /**
     * @return параметры задания (не нужны)
     */
    public static String payload() {
        return PAYLOAD;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        List<Site> sites = repository.nextSites(properties.sitesPerTask(), properties.recheckAfter());
        for (Site site : sites) {
            check(site);
        }
        if (sites.size() == properties.sitesPerTask()) {
            taskService.enqueue(TYPE, taskKey("site-" + sites.get(sites.size() - 1).id()), PAYLOAD);
        }
        return new TaskOutcome.Done();
    }

    private void check(Site site) {
        URI home = URI.create(properties.scheme() + "://" + site.host() + "/");
        Optional<Document> homePage = page(home);
        if (homePage.isEmpty()) {
            repository.record(site, Set.of(), CheckResult.UNREACHABLE);
            return;
        }
        Set<Board> boards = CareerLinks.boards(homePage.get());
        Optional<String> careerUrl = CareerLinks.careerPage(homePage.get(), site.host());
        if (boards.isEmpty() && careerUrl.isPresent()) {
            boards = careerBoards(URI.create(careerUrl.get()));
        }
        CheckResult result = !boards.isEmpty() ? CheckResult.SOURCE_FOUND
                : careerUrl.isPresent() ? CheckResult.FORMAT_UNSUPPORTED : CheckResult.NO_CAREER_PAGE;
        repository.record(site, boards, result);
        LOG.info("Site {} of company {}: {} {}", site.host(), site.companyId(), result, boards);
    }

    /**
     * Доски на кадровой странице; нет — сама страница как источник {@code jobposting}, если на ней или
     * на первых страницах вакансий есть разметка {@code JobPosting}.
     */
    private Set<Board> careerBoards(URI careerUrl) {
        Optional<Document> careerPage = page(careerUrl);
        if (careerPage.isEmpty()) {
            return Set.of();
        }
        Set<Board> boards = CareerLinks.boards(careerPage.get());
        if (!boards.isEmpty() || careerUrl.toString().length() > MAX_BOARD) {
            return boards;
        }
        boolean marked = CareerLinks.hasJobPosting(careerPage.get()) || CareerLinks
                .vacancyLinks(careerPage.get(), careerUrl, VACANCY_CHECKS).stream()
                .map(link -> page(URI.create(link)))
                .anyMatch(vacancy -> vacancy.isPresent() && CareerLinks.hasJobPosting(vacancy.get()));
        return marked ? Set.of(new Board(JobPostingAdapter.PROVIDER, careerUrl.toString())) : Set.of();
    }

    private Optional<Document> page(URI uri) {
        HttpResult result = http.get(uri);
        return result instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), uri.toString())) : Optional.empty();
    }
}
