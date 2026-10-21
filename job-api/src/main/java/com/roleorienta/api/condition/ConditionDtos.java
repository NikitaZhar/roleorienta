package com.roleorienta.api.condition;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Данные запросов и ответов условий поиска и справочников.
 */
public final class ConditionDtos {

    /** Позиций в условиях не больше (§126). */
    public static final int MAX_POSITIONS = 10;

    private ConditionDtos() {
    }

    /**
     * Условия поиска в запросе.
     *
     * @param countries    страны (ISO 3166-1 alpha-2), хотя бы одна поддерживаемая
     * @param positions    коды позиций словаря, от 1 до 10 ({@code MAX_POSITIONS}, §126); повторы не учитываются
     * @param format       формат; {@code null} — не задан
     * @param portionLimit лимит порции 1–100; {@code null} — по умолчанию
     */
    public record ConditionRequest(@NotEmpty List<@NotBlank String> countries,
            @NotEmpty @Size(max = MAX_POSITIONS) List<@NotBlank String> positions,
            WorkFormat format, @Min(1) @Max(100) Integer portionLimit) {
    }

    /**
     * Условия поиска в ответе.
     *
     * @param countries    страны
     * @param positions    позиции, по коду: с названием и признаком «включена» — выключенная остаётся в условиях
     *                     (§160) и показывается названием с пометкой, а не кодом (аудит §165)
     * @param format       формат; {@code null} — не задан
     * @param portionLimit лимит порции
     */
    public record ConditionResponse(List<String> countries, List<ChosenPosition> positions, WorkFormat format,
            int portionLimit) {
    }

    /**
     * Позиция в условиях.
     *
     * @param code   код
     * @param name   название
     * @param active включена; {@code false} — выключена администратором (§160)
     */
    public record ChosenPosition(String code, String name, boolean active) {
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
