package com.roleorienta.api.dictionary;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Запросы и ответы предложений позиций (бизнес-описание §3; технический документ §8; стенограмма §161).
 */
public final class SuggestionDtos {

    /** Наибольшая длина текста предложения и причины отказа. */
    static final int MAX_TEXT = 500;

    private SuggestionDtos() {
    }

    /** Состояние предложения. */
    public enum State {
        /** На рассмотрении. */
        PENDING,
        /** Позиция добавлена в словарь (или уже была в нём). */
        ADDED,
        /** Отклонено. */
        REJECTED
    }

    /**
     * Предложение пользователя.
     *
     * @param text что предлагается: название позиции и примеры названий вакансий
     */
    public record SuggestionRequest(@NotBlank @Size(max = MAX_TEXT) String text) {
    }

    /**
     * Предложение.
     *
     * @param id        предложение
     * @param text      текст пользователя
     * @param decision  состояние и решение администратора
     * @param createdAt когда предложено
     */
    public record SuggestionItem(long id, String text, Decision decision, Instant createdAt) {
    }

    /**
     * Состояние и решение администратора.
     *
     * @param state        состояние
     * @param positionCode код добавленной позиции ({@code ADDED}); иначе {@code null}
     * @param positionName её название; иначе {@code null}
     * @param reason       причина отказа ({@code REJECTED}, необязательна); иначе {@code null}
     * @param decidedAt    когда решено; на рассмотрении — {@code null}
     */
    public record Decision(State state, String positionCode, String positionName, String reason, Instant decidedAt) {
    }

    /**
     * Предложение для администратора.
     *
     * @param suggestion предложение
     * @param email      почта пользователя
     */
    public record AdminSuggestionItem(SuggestionItem suggestion, String email) {
    }

    /**
     * Решение администратора; можно изменить прежнее.
     *
     * @param decision {@code ADDED} или {@code REJECTED}
     * @param position код позиции словаря — для {@code ADDED}
     * @param reason   причина — для {@code REJECTED}, необязательна
     */
    public record DecisionRequest(@NotNull State decision, String position, @Size(max = MAX_TEXT) String reason) {
    }
}
