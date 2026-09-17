package com.roleorienta.api.auth;

import com.roleorienta.api.auth.AuthDtos.LoginRequest;
import com.roleorienta.api.auth.AuthDtos.MeResponse;
import com.roleorienta.api.auth.AuthDtos.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Эндпоинты модуля auth (§9): регистрация, вход, текущий пользователь, выдача CSRF-токена.
 * Выход обрабатывает Spring Security ({@code POST /api/v1/auth/logout}), см. {@code SecurityConfig}.
 *
 * <p>Контроллер тонкий (§3.3): создание пользователя делегирует {@link AppUserService},
 * сверку пароля — {@link AuthenticationManager}. Путь версионирован ({@code /api/v1}).
 * Тело ошибок — {@code application/problem+json} (включено {@code spring.mvc.problemdetails}).</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AppUserService appUserService;
    private final AppUserRepository users;

    /** Сохранение контекста безопасности в HTTP-сессию (сессия — в PostgreSQL). */
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    /**
     * @param authenticationManager проверка логина/пароля
     * @param appUserService        создание пользователей
     * @param users                 чтение пользователя для ответа {@code /me}
     */
    public AuthController(AuthenticationManager authenticationManager,
                          AppUserService appUserService,
                          AppUserRepository users) {
        this.authenticationManager = authenticationManager;
        this.appUserService = appUserService;
        this.users = users;
    }

    /**
     * Регистрация нового пользователя (роль {@code USER}).
     *
     * @param request email и пароль (валидируются)
     * @return {@code 201 Created} с данными пользователя
     * @throws ResponseStatusException {@code 409}, если email уже занят (из сервиса)
     */
    @PostMapping("/register")
    public ResponseEntity<MeResponse> register(@Valid @RequestBody RegisterRequest request) {
        AppUser user = appUserService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MeResponse(user.getId(), user.getEmail(), user.getRole()));
    }

    /**
     * Вход по email и паролю: устанавливает аутентификацию в сессию.
     *
     * <p>После успешной проверки контекст безопасности сохраняется в сессию явно —
     * в Spring Security это требуется при аутентификации из контроллера, иначе
     * следующий запрос не будет считаться аутентифицированным.
     * https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html</p>
     *
     * @param request  email и пароль
     * @param httpRequest  HTTP-запрос (для сохранения контекста в сессию)
     * @param httpResponse HTTP-ответ (для записи cookie сессии)
     * @return {@code 200} с данными вошедшего пользователя
     * @throws ResponseStatusException {@code 401} при неверных учётных данных
     */
    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest request,
                            HttpServletRequest httpRequest,
                            HttpServletResponse httpResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверные email или пароль");
        }

        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return me(authentication);
    }

    /**
     * Текущий пользователь. Достижим только для аутентифицированного запроса
     * (иначе цепочка безопасности вернёт {@code 401} до контроллера).
     *
     * @param authentication текущая аутентификация (email в {@code name})
     * @return данные пользователя
     */
    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        AppUser user = users.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Сессия недействительна"));
        return new MeResponse(user.getId(), user.getEmail(), user.getRole());
    }

    /**
     * Безопасный GET для получения CSRF-токена: побочный эффект — cookie {@code XSRF-TOKEN}
     * (её выставляет фильтр цепочки безопасности). Тела нет.
     *
     * @return {@code 204 No Content}
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }
}
