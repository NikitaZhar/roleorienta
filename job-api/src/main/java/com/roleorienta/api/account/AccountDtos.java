package com.roleorienta.api.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    /**
     * Регистрация и вход.
     *
     * @param email    email
     * @param password пароль: от 8 до 100 символов
     * @param language язык интерфейса, выбранный на экране входа, — только при регистрации; {@code null} —
     *                 английский; при входе не учитывается (язык — из учётной записи)
     */
    public record Credentials(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 100) String password,
            @Pattern(regexp = LANGUAGES) String language) {
    }

    /**
     * Настройки пользователя (раздел «Settings», §162; частота сводки — шаг 8).
     *
     * @param language язык интерфейса: {@code en} или {@code ru}
     */
    public record Settings(@NotNull @Pattern(regexp = LANGUAGES) String language) {
    }

    /**
     * Учётная запись в ответе.
     *
     * @param id       id пользователя
     * @param email    email
     * @param language язык интерфейса: {@code en} или {@code ru}
     */
    public record Account(long id, String email, String language) {

        static Account of(UserAccount account) {
            return new Account(account.getId(), account.getEmail(), account.getLanguage());
        }
    }
}
