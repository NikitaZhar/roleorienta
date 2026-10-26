package com.roleorienta.worker.career;

import com.roleorienta.worker.adapter.careerpage.CareerPageAdapter;
import com.roleorienta.worker.adapter.careerpage.CareerPageList;
import com.roleorienta.worker.adapter.jobposting.JobPostingAdapter;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/**
 * Страницы сайтов и источники на кадровой странице — общее у проверки сайтов ({@link CareerScanHandler}) и проверки
 * адреса кадровой страницы, введённого пользователем ({@link CareerRequestHandler}, §137). Страницы читаются
 * {@link ExternalHttpClient} (robots.txt, бюджет на хост, защита от SSRF).
 */
@Component
public class CareerPages {

    /** Сколько первых ссылок вглубь кадровой страницы проверяется на разметку {@code JobPosting}. */
    static final int VACANCY_CHECKS = 3;

    /** Длина {@code source.board} в схеме. */
    static final int MAX_BOARD = 200;

    private final ExternalHttpClient http;

    /**
     * @param http внешний HTTP-клиент
     */
    public CareerPages(ExternalHttpClient http) {
        this.http = http;
    }

    /**
     * @param uri адрес
     * @return ответ сайта
     */
    public HttpResult get(URI uri) {
        return http.get(uri);
    }

    /**
     * @param uri адрес
     * @return открывшаяся страница с конечным адресом после переадресаций; пусто — не открылась
     */
    public Optional<Document> page(URI uri) {
        HttpResult result = http.get(uri);
        return result instanceof HttpResult.Success success
                ? Optional.of(Jsoup.parse(success.body(), success.locationOr(uri).toString())) : Optional.empty();
    }

    /**
     * Доски на кадровой странице; нет — сама страница как источник {@code jobposting}, если на ней или
     * на первых страницах вакансий есть разметка {@code JobPosting}; нет и её — один шаг вглубь по
     * кадровой ссылке ({@code deeper}); ничего и там — сама страница как источник {@code careerpage}, если на ней
     * фраза «вакансий нет» или распознан и подтверждён текстом вакансий список (§136, §183; фраза проверяется первой —
     * без запросов к страницам вакансий, аудит §197). Хост и путь кадровой страницы — по её конечному адресу после
     * переадресаций (аудит §66).
     *
     * @param careerPage открытая кадровая страница
     * @param deeper     пробовать ли шаг вглубь
     * @return найденные источники; пусто — читаемого нет
     */
    public Set<Board> boards(Document careerPage, boolean deeper) {
        Set<Board> boards = CareerLinks.boards(careerPage);
        if (!boards.isEmpty()) {
            return boards;
        }
        URI actual = URI.create(careerPage.location());
        if (actual.toString().length() <= MAX_BOARD && (CareerLinks.hasJobPosting(careerPage) || CareerLinks
                .vacancyLinks(careerPage, actual, VACANCY_CHECKS).stream()
                .map(link -> page(URI.create(link)))
                .anyMatch(vacancy -> vacancy.isPresent() && CareerLinks.hasJobPosting(vacancy.get())))) {
            return Set.of(new Board(JobPostingAdapter.PROVIDER, actual.toString()));
        }
        Optional<String> next = deeper ? CareerLinks.deeperCareerPage(careerPage, actual) : Optional.empty();
        Set<Board> deeperBoards = next.flatMap(link -> page(URI.create(link))).map(page -> boards(page, false))
                .orElse(Set.of());
        if (!deeperBoards.isEmpty()) {
            return deeperBoards;
        }
        List<String> listed = CareerPageList.vacancies(careerPage).stream().map(CareerPageList.Item::url).toList();
        return actual.toString().length() <= MAX_BOARD && (CareerPageList.saysNoVacancies(careerPage)
                || CareerPageList.confirmed(listed, this::page))
                ? Set.of(new Board(CareerPageAdapter.PROVIDER, actual.toString())) : Set.of();
    }
}
