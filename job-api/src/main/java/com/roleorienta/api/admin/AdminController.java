package com.roleorienta.api.admin;

import com.roleorienta.api.account.SecurityConfig;
import com.roleorienta.api.account.SessionSignIn;
import com.roleorienta.api.admin.AdminDtos.Admin;
import com.roleorienta.api.admin.AdminDtos.AdminCredentials;
import com.roleorienta.api.admin.AdminDtos.MetricsTable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Администратор (§132): отдельный вход и показатели ядра (бизнес-описание §7.3; технический документ §8, §11).
 * Выход — общий {@code POST /api/v1/auth/logout}. Доступ к {@code /api/v1/admin/**}, кроме входа, — только роль
 * {@code ADMIN} ({@link SecurityConfig}).
 */
@RestController
public class AdminController {

    /** Дней в таблице по умолчанию. */
    static final int DEFAULT_DAYS = 14;

    /** Больше дней за запрос не отдаётся. */
    static final int MAX_DAYS = 90;

    private final AdminAccount admin;
    private final SessionSignIn sessionSignIn;
    private final AdminMetricsService metrics;

    /**
     * @param admin         учётная запись администратора
     * @param sessionSignIn сохранение входа в HTTP-сессии
     * @param metrics       таблица показателей ядра
     */
    public AdminController(AdminAccount admin, SessionSignIn sessionSignIn, AdminMetricsService metrics) {
        this.admin = admin;
        this.sessionSignIn = sessionSignIn;
        this.metrics = metrics;
    }

    /**
     * Вход администратора: роль {@code ADMIN} в HTTP-сессии; id сессии меняется.
     *
     * @param credentials имя и пароль
     * @param request     запрос
     * @param response    ответ
     * @return администратор; {@code 401} — неверные имя или пароль либо вход администратора выключен
     */
    @PostMapping("/api/v1/admin/login")
    public Admin login(@Valid @RequestBody AdminCredentials credentials, HttpServletRequest request,
            HttpServletResponse response) {
        sessionSignIn.signIn(admin.authenticate(credentials.username(), credentials.password()), request, response);
        return new Admin(AdminAccount.USERNAME);
    }

    /**
     * @return вошедший администратор (проверка входа для SPA)
     */
    @GetMapping("/api/v1/admin/me")
    public Admin me() {
        return new Admin(AdminAccount.USERNAME);
    }

    /**
     * Показатели ядра страны за последние дни с показателями.
     *
     * @param country страна; нет — первая страна с кодом ISO
     * @param days    сколько дней: 1–{@value #MAX_DAYS}, значение вне пределов приводится к ним
     * @return таблица показателей
     */
    @GetMapping("/api/v1/admin/metrics")
    public MetricsTable metrics(@RequestParam(required = false) String country,
            @RequestParam(defaultValue = "" + DEFAULT_DAYS) int days) {
        return metrics.table(country, Math.max(1, Math.min(days, MAX_DAYS)));
    }
}
