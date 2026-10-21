package com.roleorienta.api.vacancy;

import java.time.LocalDate;
import java.util.List;

/**
 * Правила смены статуса вакансии у пользователя (бизнес-описание §6; решения владельца 2026-10-20 и 2026-10-21,
 * стенограмма §156). Без БД: на входе текущий статус и история, на выходе — что записать.
 *
 * <ul>
 *   <li>«Просмотрена» — только у новой вакансии (текущего статуса нет); иначе ничего.</li>
 *   <li>Статус пользователя, равный текущему, — ничего (повтор запроса). Иначе он становится текущим; строка
 *       истории заменяет последнюю, если та — статус пользователя того же дня, иначе добавляется.</li>
 *   <li>«Вернуть» — только из «Не интересно»: добавляется строка «Возвращена», текущим становится последний статус
 *       истории до «Не интересно» (кроме «Не интересно» и «Возвращена»); такого нет — «Просмотрена».</li>
 * </ul>
 */
final class StatusRules {

    private StatusRules() {
    }

    /**
     * @param current   текущий статус; {@code null} — вакансия новая
     * @param history   строки истории по времени
     * @param requested запрошенный статус
     * @param today     сегодня по часовому поясу пользователя
     * @return что записать; {@link Change#NONE} — ничего
     */
    static Change change(StatusEvent current, List<Line> history, StatusEvent requested, LocalDate today) {
        if (requested == StatusEvent.VIEWED) {
            return current == null ? new Change(StatusEvent.VIEWED, null, StatusEvent.VIEWED) : Change.NONE;
        }
        if (requested == StatusEvent.RETURNED) {
            return current == StatusEvent.NOT_INTERESTED
                    ? new Change(StatusEvent.RETURNED, null, beforeNotInterested(history)) : Change.NONE;
        }
        if (requested == current) {
            return Change.NONE;
        }
        Line last = history.isEmpty() ? null : history.getLast();
        Long replaced = last != null && last.event().chosenByUser() && last.day().equals(today) ? last.id() : null;
        return new Change(requested, replaced, requested);
    }

    private static StatusEvent beforeNotInterested(List<Line> history) {
        for (Line line : history.reversed()) {
            if (line.event() != StatusEvent.NOT_INTERESTED && line.event() != StatusEvent.RETURNED) {
                return line.event();
            }
        }
        return StatusEvent.VIEWED;
    }

    /**
     * Строка истории.
     *
     * @param id    строка
     * @param event статус
     * @param day   день пользователя, когда поставлен
     */
    record Line(long id, StatusEvent event, LocalDate day) {
    }

    /**
     * Что записать.
     *
     * @param line     статус новой (или заменяющей) строки истории; {@code null} — ничего
     * @param replaced строка истории, которую заменяет новая; {@code null} — строка добавляется
     * @param current  новый текущий статус
     */
    record Change(StatusEvent line, Long replaced, StatusEvent current) {

        /** Ничего не меняется. */
        static final Change NONE = new Change(null, null, null);
    }
}
