package com.roleorienta.worker.career;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleorienta.worker.career.CareerRequestRepository.Reason;
import com.roleorienta.worker.career.CareerRequestRepository.Request;
import com.roleorienta.worker.career.CareerScanRepository.Role;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code CHECK_CAREER_URL} (§137; бизнес-описание §5): адрес кадровой страницы, введённый пользователем,
 * проверяется так же, как найденный автоматически ({@link CareerPages#boards}): сам адрес — доска системы найма или на
 * странице есть доски, разметка {@code JobPosting}, собственный список вакансий. Найдено и есть основание
 * использования — источники подключаются к компании (общие для всех пользователей), запрос «подключена»; иначе — «не
 * подключена» с причиной. Временный отказ страницы — тоже «не подключена» (страница не открылась): пользователь
 * вводит адрес снова. Собственный список вакансий и разметка ищутся только на кадровой странице
 * ({@link CareerLinks#isCareerPage}; аудит §143: главная с новостями — не список вакансий); ссылки на доски систем
 * найма — на любой странице. Параметры: {@code {"requestId": N}}.
 */
@Component
public class CareerRequestHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "CHECK_CAREER_URL";

    /** Начало ключа задания: за ним — номер запроса. */
    public static final String KEY_PREFIX = "career-request:";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = LoggerFactory.getLogger(CareerRequestHandler.class);

    private final CareerPages pages;
    private final CareerRequestRepository requests;
    private final CareerScanRepository sources;

    /**
     * @param pages    страницы сайтов и источники кадровой страницы
     * @param requests запросы пользователей
     * @param sources  подключение источников, основания использования
     */
    public CareerRequestHandler(CareerPages pages, CareerRequestRepository requests, CareerScanRepository sources) {
        this.pages = pages;
        this.requests = requests;
        this.sources = sources;
    }

    /**
     * @param requestId запрос
     * @return JSON параметров задания
     */
    public static String payload(long requestId) {
        return "{\"requestId\":" + requestId + "}";
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        Optional<Request> found = requests.pendingRequest(requestId(task));
        if (found.isEmpty()) {
            return new TaskOutcome.Done();
        }
        Request request = found.get();
        Reason reason = check(request);
        requests.finish(request.id(), reason);
        LOG.info("Career page {} of company {}: {}", request.careerUrl(), request.companyId(),
                reason == null ? "CONNECTED" : reason);
        return new TaskOutcome.Done();
    }

    /**
     * @return причина неудачи; {@code null} — источники подключены
     */
    private Reason check(Request request) {
        URI uri = uri(request.careerUrl());
        if (uri == null) {
            return Reason.UNREACHABLE;
        }
        HttpResult result = pages.get(uri);
        if (!(result instanceof HttpResult.Success success)) {
            return result instanceof HttpResult.PermanentFailure failure
                    && failure.kind() == HttpResult.Kind.USE_FORBIDDEN ? Reason.USE_FORBIDDEN : Reason.UNREACHABLE;
        }
        Document page = Jsoup.parse(success.body(), success.locationOr(uri).toString());
        boolean careerPage = CareerLinks.isCareerPage(page);
        Set<Board> boards = new LinkedHashSet<>();
        CareerLinks.board(uri.toString()).ifPresent(boards::add);
        boards.addAll(CareerLinks.boards(page));
        if (boards.isEmpty() && careerPage) {
            boards.addAll(pages.boards(page, true));
        }
        if (boards.isEmpty()) {
            return careerPage ? Reason.FORMAT_UNSUPPORTED : Reason.NO_CAREER_PAGE;
        }
        Set<String> providers = sources.permittedProviders();
        Set<Board> permitted = boards.stream().filter(board -> providers.contains(board.provider()))
                .collect(Collectors.toSet());
        Optional<Role> role = sources.permittedRole(request.companyId());
        if (permitted.isEmpty() || role.isEmpty()) {
            return Reason.USE_FORBIDDEN;
        }
        sources.connectManually(request.companyId(), permitted, role.get());
        return null;
    }

    private static URI uri(String text) {
        try {
            URI uri = new URI(text.strip());
            return uri.getHost() == null || uri.getScheme() == null
                    || !uri.getScheme().matches("(?i)https?") ? null : uri;
        } catch (URISyntaxException malformed) {
            return null;
        }
    }

    private static long requestId(TaskRecord task) {
        try {
            return JSON.readTree(task.payload()).path("requestId").asLong();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed CHECK_CAREER_URL payload: " + task.payload(), exception);
        }
    }
}
