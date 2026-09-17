package com.roleorienta.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO модуля auth (§9): только данные, без логики (контракт §3.3). Вложенные записи —
 * чтобы не плодить файлы под каждый мелкий тип (§3.10). Валидация полей — аннотациями
 * Bean Validation (starter-validation уже подключён).
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * Запрос регистрации: email и пароль.
     *
     * @param email    email нового пользователя (валидный, обязателен)
     * @param password пароль (8–100 символов; ограничение сверху — предел bcrypt по длине входа)
     */
    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password) {
    }

    /**
     * Запрос входа: email и пароль.
     *
     * @param email    email пользователя
     * @param password пароль
     */
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    /**
     * Ответ о текущем пользователе (эндпоинт {@code /me}).
     *
     * @param id    идентификатор пользователя
     * @param email email пользователя
     * @param role  роль пользователя
     */
    public record MeResponse(Long id, String email, UserRole role) {
    }
}
