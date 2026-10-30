package com.roleorienta.api.vacancy;

import com.roleorienta.api.account.AccountService;
import com.roleorienta.api.vacancy.VacancyDtos.ResultFilter;
import com.roleorienta.api.vacancy.VacancyDtos.ResultFilters;
import com.roleorienta.api.vacancy.VacancyDtos.StatusRequest;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyDetails;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyPage;
import com.roleorienta.api.vacancy.VacancyService.View;
import com.roleorienta.api.web.RequestLanguage;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Результаты поиска, «Мои отклики», «Не интересно», сведения о вакансии и статусы вакансии вошедшего пользователя
 * (технический документ §8; бизнес-описание §6, сценарий 13; стенограмма §156). Пользователь — из сессии; чужая или
 * неизвестная вакансия — {@code 404}.
 */
@RestController
public class VacancyController {

    private static final String NOT_FOUND = "Vacancy not found";

    private final VacancyService vacancies;
    private final AccountService accounts;

    /**
     * @param vacancies списки, сведения, статусы
     * @param accounts  учётные записи
     */
    public VacancyController(VacancyService vacancies, AccountService accounts) {
        this.vacancies = vacancies;
        this.accounts = accounts;
    }

    /**
     * @param authentication вошедший пользователь
     * @param page           номер страницы, с 1 (меньше — первая)
     * @param size           строк на странице (1–100)
     * @param position       код позиции или группы условий — только подходящие к ней (§194, §204); нет — все
     * @param since          {@code true} — только пришедшие после прошлого захода (§200)
     * @return страница результатов по текущим условиям (новые и просмотренные), открытые позже сверху, общее число и
     *         число новых; условия не заданы — пусто. Открытие записывается как заход в список (§191) — отдельным
     *         вызовом до чтения страницы: чтение идёт в транзакции {@code REPEATABLE READ}, запись захода — вне её
     *         ({@link VacancyService#visit}); вызов одного метода сервиса из другого внутри класса прошёл бы мимо
     *         транзакционного прокси Spring, поэтому порядок задан здесь
     */
    @GetMapping("/api/v1/me/vacancies")
    public VacancyPage list(Authentication authentication,
            @RequestParam(defaultValue = "" + VacancyService.FIRST_PAGE) int page,
            @RequestParam(defaultValue = "" + VacancyService.DEFAULT_SIZE) int size,
            @RequestParam(required = false) String position,
            @RequestParam(defaultValue = "false") boolean since) {
        long userId = userId(authentication);
        return vacancies.delivered(userId, new View(page, size, RequestLanguage.current()),
                vacancies.visit(userId), new ResultFilter(position, since));
    }

    /**
     * @param authentication вошедший пользователь
     * @return метки отбора над результатами: позиции и группы условий с числом видимых вакансий (§194) и сколько пришло
     *         после прошлого захода (§200); условия не заданы — пусто. Открытие записывается как заход, как у списка
     */
    @GetMapping("/api/v1/me/vacancies/filters")
    public ResultFilters filters(Authentication authentication) {
        long userId = userId(authentication);
        return vacancies.filters(userId, vacancies.visit(userId), RequestLanguage.current());
    }

    /**
     * @param authentication вошедший пользователь
     * @param id             вакансия
     * @return сведения о вакансии (без полного текста) со статусом и историей; {@code 404} — не выдавалась
     *         пользователю и нет его статуса
     */
    @GetMapping("/api/v1/me/vacancies/{id}")
    public VacancyDetails details(Authentication authentication, @PathVariable long id) {
        return vacancies.details(userId(authentication), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    /**
     * Смена статуса вакансии (правила — {@link StatusRules}).
     *
     * @param authentication вошедший пользователь
     * @param id             вакансия
     * @param request        статус и часовой пояс браузера
     * @return {@code 204}, и повтор — тоже {@code 204}; {@code 400} — неизвестный статус или часовой пояс; {@code 404}
     *         — чужая или неизвестная вакансия
     */
    @PutMapping("/api/v1/me/vacancies/{id}/status")
    public ResponseEntity<Void> status(Authentication authentication, @PathVariable long id,
            @Valid @RequestBody StatusRequest request) {
        return done(vacancies.setStatus(userId(authentication), id, request.status(), request.zone()));
    }

    /**
     * @param authentication вошедший пользователь
     * @param page           номер страницы, с 1 (меньше — первая)
     * @param size           строк на странице (1–100)
     * @param group          группа откликов (§217): нет или {@code ACTIVE} — действующие («Interview» сверху),
     *                       {@code SILENT} — «Applied» без ответа 3+ недели, {@code FINISHED} — завершённые
     *                       («Employer declined», закрытые работодателем, без ответа 6+ недель); другое значение —
     *                       {@code 400}
     * @param query          параметр {@code q} — поиск по позиции, работодателю и агентству без регистра и
     *                       диакритики (§226): задан —
     *                       отклики всех групп одним списком, {@code group} не учитывается
     * @return «Мои отклики» одной группы (или найденные): статус изменён позже сверху, и общее число
     */
    @GetMapping("/api/v1/me/responses")
    public VacancyPage responses(Authentication authentication,
            @RequestParam(defaultValue = "" + VacancyService.FIRST_PAGE) int page,
            @RequestParam(defaultValue = "" + VacancyService.DEFAULT_SIZE) int size,
            @RequestParam(defaultValue = "ACTIVE") ResponseGroup group,
            @RequestParam(name = "q", required = false) String query) {
        return vacancies.responses(userId(authentication), page, size, group, query);
    }

    /**
     * @param authentication вошедший пользователь
     * @param page           номер страницы, с 1 (меньше — первая)
     * @param size           строк на странице (1–100)
     * @param query          параметр {@code q} — поиск по позиции, работодателю и агентству без регистра и
     *                       диакритики (§226); нет — все
     * @return «Не интересно» (или найденные): отмеченные позже сверху, и общее число
     */
    @GetMapping("/api/v1/me/unsuitable")
    public VacancyPage unsuitable(Authentication authentication,
            @RequestParam(defaultValue = "" + VacancyService.FIRST_PAGE) int page,
            @RequestParam(defaultValue = "" + VacancyService.DEFAULT_SIZE) int size,
            @RequestParam(name = "q", required = false) String query) {
        return vacancies.notInterested(userId(authentication), page, size, query);
    }

    private static ResponseEntity<Void> done(boolean found) {
        if (!found) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    private long userId(Authentication authentication) {
        return accounts.byEmail(authentication.getName()).getId();
    }
}
