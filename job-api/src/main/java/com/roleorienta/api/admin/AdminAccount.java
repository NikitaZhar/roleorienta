package com.roleorienta.api.admin;

import com.roleorienta.api.account.SecurityConfig;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
     * Проверяет имя и пароль администратора.
     *
     * @param username имя
     * @param password пароль
     * @return проверенный вход с ролью {@code ADMIN}
     * @throws BadCredentialsException вход администратора выключен или имя и пароль неверны
     */
    public Authentication authenticate(String username, String password) {
        if (passwordHash == null || !USERNAME.equals(username) || !passwordEncoder.matches(password, passwordHash)) {
            throw new BadCredentialsException("Invalid administrator credentials");
        }
        return UsernamePasswordAuthenticationToken.authenticated(USERNAME, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + SecurityConfig.ADMIN_ROLE)));
    }
}
