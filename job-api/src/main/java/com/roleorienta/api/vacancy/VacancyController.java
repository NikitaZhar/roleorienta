package com.roleorienta.api.vacancy;

import com.roleorienta.api.account.AccountService;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyDetails;
import com.roleorienta.api.vacancy.VacancyDtos.VacancyPage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Накопленный список, сведения о вакансии и отметки «не подходит» вошедшего пользователя (технический документ
 * §8; бизнес-описание §6, сценарий 13). Пользователь — из сессии; чужая или неизвестная вакансия — {@code 404}.
 */
@RestController
public class VacancyController {

    private static final String NOT_FOUND = "Vacancy not found";

    private final VacancyService vacancies;
    private final AccountService accounts;

    /**
     * @param vacancies список, сведения, отметки
     * @param accounts  учётные записи
     */
    public VacancyController(VacancyService vacancies, AccountService accounts) {
        this.vacancies = vacancies;
        this.accounts = accounts;
    }

    /**
     * @param authentication вошедший пользователь
     * @param cursor         курсор страницы; нет — первая
     * @param limit          строк на странице (1–100)
     * @return накопленный список по текущим условиям, новые сверху; условия не заданы — пусто;
     *         {@code 400} — курсор испорчен или от другой версии условий
     */
    @GetMapping("/api/v1/me/vacancies")
    public VacancyPage list(Authentication authentication, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + VacancyService.DEFAULT_LIMIT) int limit) {
        return vacancies.delivered(userId(authentication), cursor, limit);
    }

    /**
     * @param authentication вошедший пользователь
     * @param id             вакансия
     * @return сведения о вакансии (без полного текста); {@code 404} — не выдавалась пользователю и не отмечена
     */
    @GetMapping("/api/v1/me/vacancies/{id}")
    public VacancyDetails details(Authentication authentication, @PathVariable long id) {
        return vacancies.details(userId(authentication), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    /**
     * @param authentication вошедший пользователь
     * @param id             вакансия
     * @return {@code 204}; повтор — тоже {@code 204}; {@code 404} — чужая или неизвестная вакансия
     */
    @PutMapping("/api/v1/me/vacancies/{id}/unsuitable")
    public ResponseEntity<Void> mark(Authentication authentication, @PathVariable long id) {
        return done(vacancies.mark(userId(authentication), id));
    }

    /**
     * @param authentication вошедший пользователь
     * @param id             вакансия
     * @return {@code 204}; отметки не было — тоже {@code 204}; {@code 404} — чужая или неизвестная вакансия
     */
    @DeleteMapping("/api/v1/me/vacancies/{id}/unsuitable")
    public ResponseEntity<Void> unmark(Authentication authentication, @PathVariable long id) {
        return done(vacancies.unmark(userId(authentication), id));
    }

    /**
     * @param authentication вошедший пользователь
     * @param cursor         курсор страницы; нет — первая
     * @param limit          строк на странице (1–100)
     * @return отмеченные вакансии, последние сверху
     */
    @GetMapping("/api/v1/me/unsuitable")
    public VacancyPage unsuitable(Authentication authentication, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + VacancyService.DEFAULT_LIMIT) int limit) {
        return vacancies.marked(userId(authentication), cursor, limit);
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
