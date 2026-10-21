package com.roleorienta.api.company;

import com.roleorienta.api.account.AccountService;
import com.roleorienta.api.company.CompanyDtos.CareerPageInput;
import com.roleorienta.api.company.CompanyDtos.CareerRequestItem;
import com.roleorienta.api.company.CompanyDtos.CompanyItem;
import com.roleorienta.api.company.CompanyDtos.CompanyPage;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Компании по группам и адреса кадровых страниц, введённые пользователем (§137; бизнес-описание §4.2, §5; технический
 * документ §8). Пользователь — из сессии; его запросы видит только он, добавленный адрес общий для всех.
 */
@RestController
public class CompanyController {

    private final CompanyService companies;
    private final AccountService accounts;

    /**
     * @param companies компании и запросы
     * @param accounts  учётные записи
     */
    public CompanyController(CompanyService companies, AccountService accounts) {
        this.companies = companies;
        this.accounts = accounts;
    }

    /**
     * @param authentication вошедший пользователь
     * @param query          название (часть) или IČO
     * @return найденные компании с группой; {@code 400} — запрос короче 3 символов
     */
    @GetMapping("/api/v1/companies")
    public List<CompanyItem> search(Authentication authentication, @RequestParam(required = false) String query) {
        return companies.search(userId(authentication), query);
    }

    /**
     * @param authentication вошедший пользователь
     * @param page           номер страницы, с 1 (меньше — первая)
     * @param size           строк на странице (1–100)
     * @return страница сомнительных компаний («может быть» и «нет» с признаком найма, без скрытых пользователем) и
     *         общее число — страницы по номеру, как у результатов поиска (решение владельца 2026-10-21, §163)
     */
    @GetMapping("/api/v1/companies/doubtful")
    public CompanyPage doubtful(Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "" + CompanyService.DEFAULT_SIZE) int size) {
        return companies.doubtful(userId(authentication), page, size);
    }

    /**
     * @param authentication вошедший пользователь
     * @param id             компания
     * @return {@code 204}; уже скрыта — тоже {@code 204}; {@code 404} — компании нет
     */
    @PutMapping("/api/v1/me/hidden-companies/{id}")
    public ResponseEntity<Void> hide(Authentication authentication, @PathVariable long id) {
        companies.hide(userId(authentication), id, true);
        return ResponseEntity.noContent().build();
    }

    /**
     * @param authentication вошедший пользователь
     * @param id             компания
     * @return {@code 204}; не была скрыта — тоже {@code 204}; {@code 404} — компании нет
     */
    @DeleteMapping("/api/v1/me/hidden-companies/{id}")
    public ResponseEntity<Void> unhide(Authentication authentication, @PathVariable long id) {
        companies.hide(userId(authentication), id, false);
        return ResponseEntity.noContent().build();
    }

    /**
     * @param authentication вошедший пользователь
     * @return компании, которые он скрыл
     */
    @GetMapping("/api/v1/me/hidden-companies")
    public List<CompanyItem> hidden(Authentication authentication) {
        return companies.hidden(userId(authentication));
    }

    /**
     * @param authentication вошедший пользователь
     * @param id             компания
     * @param input          адрес кадровой страницы
     * @return запрос «в обработке» ({@code 201}); ошибки — {@link CompanyService#addCareerPage}
     */
    @PostMapping("/api/v1/companies/{id}/career-page")
    @ResponseStatus(HttpStatus.CREATED)
    public CareerRequestItem addCareerPage(Authentication authentication, @PathVariable long id,
            @Valid @RequestBody CareerPageInput input) {
        return companies.addCareerPage(userId(authentication), id, input.url());
    }

    /**
     * @param authentication вошедший пользователь
     * @return его запросы, новые первыми
     */
    @GetMapping("/api/v1/me/career-requests")
    public List<CareerRequestItem> requests(Authentication authentication) {
        return companies.requests(userId(authentication));
    }

    private long userId(Authentication authentication) {
        return accounts.byEmail(authentication.getName()).getId();
    }
}
