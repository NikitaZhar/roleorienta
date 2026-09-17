package com.roleorienta.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Внешняя конфигурация модуля auth (§3.4 — конфигурация извне).
 *
 * <p>Привязывается к префиксу {@code app.auth} (application.yml). Секрет
 * администратора передаётся через переменные окружения и в коде/образе не хранится
 * (§3.9). Пустые значения означают «bootstrap администратора не выполнять».</p>
 *
 * @param admin параметры учётной записи администратора для bootstrap при старте
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(Admin admin) {

    /**
     * Параметры bootstrap-администратора.
     *
     * @param email    email администратора (пусто — bootstrap пропускается)
     * @param password пароль администратора (пусто — bootstrap пропускается)
     */
    public record Admin(String email, String password) {
    }
}
