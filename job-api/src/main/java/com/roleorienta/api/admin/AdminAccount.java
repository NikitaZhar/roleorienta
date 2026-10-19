package com.roleorienta.api.admin;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Администратор (§132): одна учётная запись с именем {@value #USERNAME}, не из таблицы пользователей. Пароль из
 * настроек хешируется при запуске тем же кодировщиком, что пароли пользователей (BCrypt); в памяти — только хеш.
 */
@Component
public class AdminAccount {

    /** Имя администратора. */
    public static final String USERNAME = "admin";

    private final PasswordEncoder passwordEncoder;
    private final String passwordHash;

    /**
     * @param properties      пароль администратора
     * @param passwordEncoder хеширование паролей
     */
    public AdminAccount(AdminProperties properties, PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.passwordHash = properties.password() == null || properties.password().isBlank()
                ? null : passwordEncoder.encode(properties.password());
    }

    /**
     * @param username имя
     * @param password пароль
     * @return {@code true} — вход администратора включён, имя и пароль верны
     */
    public boolean matches(String username, String password) {
        return passwordHash != null && USERNAME.equals(username) && passwordEncoder.matches(password, passwordHash);
    }
}
