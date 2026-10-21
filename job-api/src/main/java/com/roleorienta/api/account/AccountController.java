package com.roleorienta.api.account;

import com.roleorienta.api.account.AccountDtos.Account;
import com.roleorienta.api.account.AccountDtos.Credentials;
import com.roleorienta.api.account.AccountDtos.Settings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Регистрация, вход и текущий пользователь (технический документ §8). Выход —
 * {@code POST /api/v1/auth/logout}, его обрабатывает Spring Security ({@link SecurityConfig}).
 */
@RestController
public class AccountController {

    private final AccountService accounts;
    private final AuthenticationManager authenticationManager;
    private final SessionSignIn sessionSignIn;

    /**
     * @param accounts              учётные записи
     * @param authenticationManager проверка email и пароля
     * @param sessionSignIn         сохранение входа в HTTP-сессии
     */
    public AccountController(AccountService accounts, AuthenticationManager authenticationManager,
            SessionSignIn sessionSignIn) {
        this.accounts = accounts;
        this.authenticationManager = authenticationManager;
        this.sessionSignIn = sessionSignIn;
    }

    /**
     * Регистрация; вход — отдельным запросом.
     *
     * @param credentials email, пароль и язык, выбранный на экране входа
     * @return учётная запись; {@code 409} — email занят, {@code 400} — неверные поля
     */
    @PostMapping("/api/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Account register(@Valid @RequestBody Credentials credentials) {
        return Account.of(accounts.register(credentials.email(), credentials.password(), credentials.language(),
                zone(credentials.timeZone())));
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
        sessionSignIn.signIn(authentication, request, response);
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

    /**
     * Настройки пользователя (§162, §164): язык, частота сводки на почту, часовой пояс; не переданное не меняется.
     *
     * @param authentication вошедший пользователь
     * @param settings       язык, частота, часовой пояс браузера
     * @return учётная запись; {@code 400} — неизвестный язык, частота или часовой пояс
     */
    @PutMapping("/api/v1/me/settings")
    public Account settings(Authentication authentication, @Valid @RequestBody Settings settings) {
        zone(settings.timeZone());
        return Account.of(accounts.updateSettings(authentication.getName(), settings));
    }

    /**
     * Проверка входа: часовой пояс — известное название IANA ({@code ZoneId}).
     *
     * @param timeZone часовой пояс; {@code null} — не передан
     * @return он же
     * @throws ResponseStatusException {@code 400} — неизвестный часовой пояс
     */
    private static String zone(String timeZone) {
        if (timeZone == null) {
            return null;
        }
        try {
            ZoneId.of(timeZone);
            return timeZone;
        } catch (DateTimeException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown time zone: " + timeZone);
        }
    }
}
