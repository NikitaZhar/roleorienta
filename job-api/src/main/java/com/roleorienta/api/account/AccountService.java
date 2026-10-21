package com.roleorienta.api.account;

import com.roleorienta.api.condition.InvalidFieldException;
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
     * @param language язык интерфейса; {@code null} — английский
     * @param timeZone часовой пояс браузера (IANA); {@code null} или неизвестный PostgreSQL — UTC: регистрация из-за
     *                 пояса не отказывает, его поправит сверка при следующем открытии приложения (аудит §165)
     * @return новая учётная запись
     * @throws EmailTakenException email уже зарегистрирован (в том числе одновременной регистрацией)
     */
    @Transactional
    public UserAccount register(String email, String password, String language, String timeZone) {
        String normalized = normalize(email);
        if (accounts.existsByEmail(normalized)) {
            throw new EmailTakenException();
        }
        try {
            return accounts.saveAndFlush(new UserAccount(normalized, passwordEncoder.encode(password),
                    language == null ? AccountDtos.DEFAULT_LANGUAGE : language,
                    timeZone == null || !accounts.knownTimeZone(timeZone) ? AccountDtos.DEFAULT_TIME_ZONE : timeZone));
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

    /**
     * Настройки пользователя: меняются только переданные поля.
     *
     * @param email    email вошедшего пользователя
     * @param settings язык, частота сводки, часовой пояс браузера (IANA); {@code null} — без изменения
     * @return учётная запись с новыми настройками
     * @throws InvalidFieldException часовой пояс неизвестен ({@code 400}, поле {@code /timeZone})
     */
    @Transactional
    public UserAccount updateSettings(String email, AccountDtos.Settings settings) {
        checkTimeZone(settings.timeZone());
        UserAccount account = byEmail(email);
        if (settings.language() != null) {
            account.setLanguage(settings.language());
        }
        if (settings.digestFrequency() != null) {
            account.setDigestFrequency(settings.digestFrequency());
        }
        if (settings.timeZone() != null) {
            account.setTimeZone(settings.timeZone());
        }
        return account;
    }

    /**
     * Часовой пояс — название, известное PostgreSQL ({@link UserAccountRepository#knownTimeZone}); {@code null} — не
     * передан.
     */
    private void checkTimeZone(String timeZone) {
        if (timeZone != null && !accounts.knownTimeZone(timeZone)) {
            throw new InvalidFieldException("/timeZone", "Unknown time zone");
        }
    }
}
