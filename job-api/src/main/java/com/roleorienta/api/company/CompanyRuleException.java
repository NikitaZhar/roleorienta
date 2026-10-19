package com.roleorienta.api.company;

/**
 * Нарушено правило компаний и адресов кадровых страниц (аудит §143: сервис не зависит от HTTP); перевод в ответ —
 * {@code ApiExceptionHandler}: {@link Kind#NOT_FOUND} — {@code 404}, {@link Kind#CONFLICT} — {@code 409},
 * {@link Kind#TOO_MANY} — {@code 429}.
 */
public class CompanyRuleException extends RuntimeException {

    private final Kind kind;

    /**
     * @param kind   что нарушено
     * @param detail текст для ответа
     */
    public CompanyRuleException(Kind kind, String detail) {
        super(detail);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * Вид нарушения.
     */
    public enum Kind {
        /** Компании нет. */
        NOT_FOUND,
        /** Действие не подходит к состоянию компании или запроса. */
        CONFLICT,
        /** Превышено ограничение частоты. */
        TOO_MANY
    }
}
