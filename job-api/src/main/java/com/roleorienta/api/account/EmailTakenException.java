package com.roleorienta.api.account;

/**
 * Email уже зарегистрирован — ответ {@code 409}.
 */
public class EmailTakenException extends RuntimeException {

    /**
     * Исключение с сообщением для ответа.
     */
    public EmailTakenException() {
        super("Email is already registered");
    }
}
