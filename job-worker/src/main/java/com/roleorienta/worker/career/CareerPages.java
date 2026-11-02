package com.roleorienta.worker.career;

import com.roleorienta.worker.adapter.careerpage.CareerPageAdapter;
import com.roleorienta.worker.adapter.careerpage.CareerPageList;
import com.roleorienta.worker.adapter.jobposting.JobPostingAdapter;
import com.roleorienta.worker.browser.PageRenderer;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.site.SiteVerifier;
import com.roleorienta.worker.site.SiteVerifier.Verdict;
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
 * {@link ExternalHttpClient} (robots.txt, бюджет на хост, защита от SSRF); страница после выполнения скриптов — браузером
 * без окна ({@link PageRenderer}, §236).
 */
@Component
public class CareerPages {

    /** Сколько первых ссылок вглубь кадровой страницы проверяется на разметку {@code JobPosting}. */
    static final int VACANCY_CHECKS = 3;

    /** Длина {@code source.board} в схеме. */
    static final int MAX_BOARD = 200;

    /** Сколько кадровых ссылок вглубь пробуется на кадровой странице без читаемого (§241; было — одна). */
    static final int DEEPER_PAGES = 3;

    private final ExternalHttpClient http;
    private final PageRenderer renderer;
    private final SiteVerifier verifier;

    /**
     * @param http     внешний HTTP-клиент
     * @param renderer браузер без окна ({@link PageRenderer#NONE} — без него)
     * @param verifier проверка, что сайт принадлежит компании (IČO или бренд на его странице)
     */
    public CareerPages(ExternalHttpClient http, PageRenderer renderer, SiteVerifier verifier) {
        this.http = http;
        this.renderer = renderer;
        this.verifier = verifier;
    }

    /**
     * Сайт по ссылке со страницы группы (§237) — сайт этой компании, если на его главной или странице реквизитов её
     * IČO, иначе бренд названия ({@link SiteVerifier}).
     *
     * @param host               хост сайта
     * @param registrationNumber IČO компании
     * @param name               название компании
     * @return подтверждение ({@code REGISTRATION_NUMBER} или {@code BRAND}); пусто — сайт не её или не открылся
     */
    public Optional<String> companySite(String host, String registrationNumber, String name) {
        Verdict verdict = verifier.verify(host, "", registrationNumber, name);
        return verdict == Verdict.REGISTRATION_NUMBER || verdict == Verdict.BRAND
                ? Optional.of(verdict.name()) : Optional.empty();
    }

    /**
     * @param uri адрес страницы, уже открывшейся обычным запросом
     * @return страница после выполнения скриптов; пусто — не открылась или браузер выключен
     */
    public Optional<Document> rendered(URI uri) {
        return renderer.render(uri);
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
     * на первых страницах вакансий есть разметка {@code JobPosting}; нет и её — шаг вглубь по кадровым ссылкам
     * ({@code deeper}; до {@value #DEEPER_PAGES}, ссылки на список вакансий — первыми, §241); ничего и там — сама
     * страница как источник {@code careerpage}, если на ней фраза «вакансий нет» или распознан и подтверждён текстом
     * вакансий список (§136, §183; фраза проверяется первой — без запросов к страницам вакансий, аудит §197); не
     * распознан — то же на странице в браузере без окна с нажатием «показать ещё» (список строит скрипт, §241; только
     * для самой кадровой страницы, не для шагов вглубь). Хост и путь кадровой страницы — по её конечному адресу после
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
        if (deeper) {
            for (String link : CareerLinks.deeperCareerPages(careerPage, actual, DEEPER_PAGES)) {
                Set<Board> deeperBoards = page(URI.create(link)).map(page -> boards(page, false)).orElse(Set.of());
                if (!deeperBoards.isEmpty()) {
                    return deeperBoards;
                }
            }
        }
        if (actual.toString().length() > MAX_BOARD) {
            return Set.of();
        }
        Board own = new Board(CareerPageAdapter.PROVIDER, actual.toString());
        if (CareerPageList.saysNoVacancies(careerPage) || listConfirmed(careerPage)) {
            return Set.of(own);
        }
        Optional<Document> built = deeper ? renderer.expanded(actual) : Optional.empty();
        if (built.isEmpty()) {
            return Set.of();
        }
        Set<Board> builtBoards = CareerLinks.boards(built.get());
        return !builtBoards.isEmpty() ? builtBoards : listConfirmed(built.get()) ? Set.of(own) : Set.of();
    }

    /** Список вакансий страницы распознан и подтверждён текстом страниц вакансий (§183). */
    private boolean listConfirmed(Document page) {
        List<String> listed = CareerPageList.vacancies(page).stream().map(CareerPageList.Item::url).toList();
        return CareerPageList.confirmed(listed, this::page);
    }
}
