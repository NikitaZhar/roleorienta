package com.roleorienta.api.condition;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Данные запросов и ответов условий поиска и справочников.
 */
public final class ConditionDtos {

    private ConditionDtos() {
    }

    /**
     * Условия поиска в запросе.
     *
     * @param countries    страны (ISO 3166-1 alpha-2), хотя бы одна поддерживаемая
     * @param position     код позиции словаря
     * @param format       формат; {@code null} — не задан
     * @param portionLimit лимит порции 1–100; {@code null} — по умолчанию
     */
    public record ConditionRequest(@NotEmpty List<@NotBlank String> countries, @NotBlank String position,
            WorkFormat format, @Min(1) @Max(100) Integer portionLimit) {
    }

    /**
     * Условия поиска в ответе.
     *
     * @param countries    страны
     * @param position     код позиции
     * @param format       формат; {@code null} — не задан
     * @param portionLimit лимит порции
     */
    public record ConditionResponse(List<String> countries, String position, WorkFormat format, int portionLimit) {
    }

    /**
     * Элемент справочника (страна или позиция).
     *
     * @param code код
     * @param name название
     */
    public record CatalogItem(String code, String name) {
    }
}
