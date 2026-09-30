package com.roleorienta.api.condition;

/**
 * Изменение условий без верной версии: {@code If-Match} не указан, хотя условия есть ({@code 428}),
 * или указан, но версия уже другая ({@code 412}).
 */
public class ConditionVersionException extends RuntimeException {

    private final boolean missing;

    /**
     * @param missing {@code true} — версия не указана; {@code false} — указана не та
     */
    public ConditionVersionException(boolean missing) {
        super(missing ? "If-Match with the current condition version is required"
                : "Search condition was changed, reload it");
        this.missing = missing;
    }

    public boolean isMissing() {
        return missing;
    }
}
