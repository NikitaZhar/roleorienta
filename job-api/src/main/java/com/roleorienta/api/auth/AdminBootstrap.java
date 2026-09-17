package com.roleorienta.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Bootstrap администратора при старте приложения (§9).
 *
 * <p>Если в конфигурации задан email и пароль администратора ({@code app.auth.admin.*},
 * значения — из окружения), при старте создаётся аккаунт с ролью {@code ADMIN}, но
 * только если пользователя с таким email ещё нет (идемпотентно, §3.4). Если значения
 * не заданы — шаг пропускается. Пароль в логи не попадает (§3.9); логируется лишь факт
 * и email.</p>
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthProperties properties;
    private final AppUserService appUserService;

    /**
     * @param properties     конфигурация модуля auth (данные администратора)
     * @param appUserService создание администратора
     */
    public AdminBootstrap(AuthProperties properties, AppUserService appUserService) {
        this.properties = properties;
        this.appUserService = appUserService;
    }

    /**
     * Создаёт администратора при старте, если он сконфигурирован и ещё не существует.
     *
     * @param args аргументы запуска (не используются)
     */
    @Override
    public void run(ApplicationArguments args) {
        AuthProperties.Admin admin = properties.admin();
        if (admin == null
                || !StringUtils.hasText(admin.email())
                || !StringUtils.hasText(admin.password())) {
            log.info("Bootstrap администратора пропущен: email/пароль не заданы");
            return;
        }
        boolean created = appUserService.createAdminIfAbsent(admin.email(), admin.password());
        if (created) {
            log.info("Создан администратор при bootstrap: {}", admin.email());
        } else {
            log.info("Администратор уже существует, bootstrap пропущен: {}", admin.email());
        }
    }
}
