package com.roleorienta.worker.adapter;

import com.roleorienta.worker.vacancy.FetchedPosting;

/**
 * Проверка публикации, пропавшей из списка, прочитанного с фильтром по стране (бизнес-описание
 * §4.3, технический документ §5): пропажа из такого списка ещё не значит закрытие — публикация могла
 * сменить страну.
 */
public sealed interface PostingCheck {

    /**
     * Публикация есть у источника: сведения обновляются, отсутствие не засчитывается.
     *
     * @param posting сведения публикации
     */
    record Present(FetchedPosting posting) implements PostingCheck {
    }

    /**
     * Источник подтвердил, что публикации нет: отсутствие засчитывается.
     */
    record Absent() implements PostingCheck {
    }

    /**
     * Проверить не удалось: отсутствие не засчитывается, проверка — следующим чтением.
     *
     * @param reason причина для журнала
     */
    record Unknown(String reason) implements PostingCheck {
    }
}
