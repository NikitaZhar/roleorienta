package com.roleorienta.api.condition;

/**
 * Значение поля запроса синтаксически верно, но недопустимо (например, неподдерживаемая страна) —
 * ответ {@code 400} с ошибкой поля.
 */
public class InvalidFieldException extends RuntimeException {

    private final String pointer;

    /**
     * @param pointer поле в теле запроса (JSON Pointer, RFC 6901), например {@code /countries}
     * @param detail  что не так
     */
    public InvalidFieldException(String pointer, String detail) {
        super(detail);
        this.pointer = pointer;
    }

    public String getPointer() {
        return pointer;
    }
}
