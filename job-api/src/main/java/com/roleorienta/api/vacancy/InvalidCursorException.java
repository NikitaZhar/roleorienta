package com.roleorienta.api.vacancy;

/**
 * Курсор страницы испорчен или выдан для другого списка (версия условий сменилась) — {@code 400}; клиент начинает
 * список с первой страницы.
 */
public class InvalidCursorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Исключение с постоянным текстом.
     */
    public InvalidCursorException() {
        super("Invalid or outdated page cursor");
    }
}
