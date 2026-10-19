package com.roleorienta.api.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Вход администратора ({@code app.admin.*}; §132). Пароль задаётся только снаружи — переменной окружения
 * {@code ADMIN_PASSWORD}; не задан — вход администратора выключен.
 *
 * @param password пароль администратора; {@code null} или пусто — входа нет
 */
@ConfigurationProperties("app.admin")
public record AdminProperties(String password) {

    /**
     * Пароль не попадает в журнал при выводе настроек.
     *
     * @return настройки без пароля
     */
    @Override
    public String toString() {
        return "AdminProperties[password=" + (password == null || password.isBlank() ? "not set" : "***") + "]";
    }
}
