package com.roleorienta.api.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Данные запросов и ответов учётной записи.
 */
public final class AccountDtos {

    private AccountDtos() {
    }

    /**
     * Регистрация и вход.
     *
     * @param email    email
     * @param password пароль: от 8 до 100 символов
     */
    public record Credentials(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 100) String password) {
    }

    /**
     * Учётная запись в ответе.
     *
     * @param id    id пользователя
     * @param email email
     */
    public record Account(long id, String email) {

        static Account of(UserAccount account) {
            return new Account(account.getId(), account.getEmail());
        }
    }
}
