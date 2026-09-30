package com.roleorienta.api.account;

import com.roleorienta.api.account.AccountDtos.Account;
import com.roleorienta.api.account.AccountDtos.Credentials;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Регистрация, вход и текущий пользователь (технический документ §8). Выход —
 * {@code POST /api/v1/auth/logout}, его обрабатывает Spring Security ({@link SecurityConfig}).
 */
@RestController
public class AccountController {

    private final AccountService accounts;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContexts;

    /**
     * @param accounts              учётные записи
     * @param authenticationManager проверка email и пароля
     * @param securityContexts      хранение входа в HTTP-сессии
     */
    public AccountController(AccountService accounts, AuthenticationManager authenticationManager,
            SecurityContextRepository securityContexts) {
        this.accounts = accounts;
        this.authenticationManager = authenticationManager;
        this.securityContexts = securityContexts;
    }

    /**
     * Регистрация; вход — отдельным запросом.
     *
     * @param credentials email и пароль
     * @return учётная запись; {@code 409} — email занят, {@code 400} — неверные поля
     */
    @PostMapping("/api/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Account register(@Valid @RequestBody Credentials credentials) {
        return Account.of(accounts.register(credentials.email(), credentials.password()));
    }

    /**
     * Вход: email и пароль проверяются, результат сохраняется в HTTP-сессии; id сессии меняется
     * (защита от подмены сессии).
     *
     * @param credentials email и пароль
     * @param request     запрос
     * @param response    ответ
     * @return учётная запись; {@code 401} — неверные email или пароль
     */
    @PostMapping("/api/v1/auth/login")
    public Account login(@Valid @RequestBody Credentials credentials, HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(AccountService.normalize(credentials.email()),
                        credentials.password()));
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        return Account.of(accounts.byEmail(authentication.getName()));
    }

    /**
     * @param authentication вошедший пользователь
     * @return учётная запись
     */
    @GetMapping("/api/v1/me")
    public Account me(Authentication authentication) {
        return Account.of(accounts.byEmail(authentication.getName()));
    }
}
