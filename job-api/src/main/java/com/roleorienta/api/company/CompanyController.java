package com.roleorienta.api.company;

import com.roleorienta.api.account.AccountService;
import com.roleorienta.api.company.CompanyDtos.CareerPageInput;
import com.roleorienta.api.company.CompanyDtos.CareerRequestItem;
import com.roleorienta.api.company.CompanyDtos.CompanyItem;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
     * @param query название (часть) или IČO
     * @return найденные компании с группой; {@code 400} — запрос короче 3 символов
     */
    @GetMapping("/api/v1/companies")
    public List<CompanyItem> search(@RequestParam(required = false) String query) {
        return companies.search(query);
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
