package com.roleorienta.api.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Данные запросов и ответов учётной записи.
 */
public final class AccountDtos {

    private AccountDtos() {
    }

    /** Языки интерфейса (§162): английский — по умолчанию. */
    static final String LANGUAGES = "en|ru";
    static final String DEFAULT_LANGUAGE = "en";
    /** Частота сводки на почту (§164): каждый день — по умолчанию. */
    static final String FREQUENCIES = "DAILY|WEEKDAYS|WEEKLY|NEVER";
    /** Часовой пояс, пока браузер его не передал. */
    static final String DEFAULT_TIME_ZONE = "UTC";
    /** Наибольшая длина названия часового пояса ({@code user_account.time_zone}). */
    static final int MAX_TIME_ZONE = 64;

    /**
     * Регистрация и вход.
     *
     * @param email    email
     * @param password пароль: от 8 до 100 символов
     * @param language язык интерфейса, выбранный на экране входа, — только при регистрации; {@code null} —
     *                 английский; при входе не учитывается (язык — из учётной записи)
     * @param timeZone часовой пояс браузера (IANA) — только при регистрации: время сводки на почту (§164); {@code null}
     *                 — UTC
     */
    public record Credentials(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 100) String password,
            @Pattern(regexp = LANGUAGES) String language, @Size(max = MAX_TIME_ZONE) String timeZone) {
    }

    /**
     * Настройки пользователя (раздел «Settings», §162, §164); {@code null} — поле не меняется.
     *
     * @param language        язык интерфейса: {@code en} или {@code ru}
     * @param digestFrequency частота сводки: {@code DAILY}, {@code WEEKDAYS}, {@code WEEKLY}, {@code NEVER}
     * @param timeZone        часовой пояс браузера (IANA): письмо приходит в 8:00 по нему
     */
    public record Settings(@Pattern(regexp = LANGUAGES) String language,
            @Pattern(regexp = FREQUENCIES) String digestFrequency, @Size(max = MAX_TIME_ZONE) String timeZone) {
    }

    /**
     * Учётная запись в ответе.
     *
     * @param id              id пользователя
     * @param email           email
     * @param language        язык интерфейса: {@code en} или {@code ru}
     * @param digestFrequency частота сводки на почту (§164)
     */
    public record Account(long id, String email, String language, String digestFrequency) {

        static Account of(UserAccount account) {
            return new Account(account.getId(), account.getEmail(), account.getLanguage(),
                    account.getDigestFrequency());
        }
    }
}
