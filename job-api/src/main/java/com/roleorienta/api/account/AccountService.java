package com.roleorienta.api.account;

import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Регистрация и поиск учётных записей.
 */
@Service
public class AccountService {

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    /**
     * @param accounts        учётные записи
     * @param passwordEncoder хеширование паролей
     */
    public AccountService(UserAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * @param email email как введён
     * @return email без пробелов по краям и в нижнем регистре — одна учётная запись на адрес
     */
    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Регистрирует учётную запись; пароль хранится только хешем.
     *
     * @param email    email
     * @param password пароль
     * @return новая учётная запись
     * @throws EmailTakenException email уже зарегистрирован (в том числе одновременной регистрацией)
     */
    @Transactional
    public UserAccount register(String email, String password) {
        String normalized = normalize(email);
        if (accounts.existsByEmail(normalized)) {
            throw new EmailTakenException();
        }
        try {
            return accounts.saveAndFlush(new UserAccount(normalized, passwordEncoder.encode(password)));
        } catch (DataIntegrityViolationException duplicate) {
            throw new EmailTakenException();
        }
    }

    /**
     * @param email email вошедшего пользователя (имя из сессии)
     * @return учётная запись
     * @throws IllegalStateException учётной записи нет (удалена после входа)
     */
    @Transactional(readOnly = true)
    public UserAccount byEmail(String email) {
        return accounts.findByEmail(normalize(email))
                .orElseThrow(() -> new IllegalStateException("No account for the signed-in user"));
    }
}
