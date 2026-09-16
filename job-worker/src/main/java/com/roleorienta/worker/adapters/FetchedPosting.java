package com.roleorienta.worker.adapters;

/**
 * Детальные поля публикации, добранные адаптером с detail-endpoint (§5 техдока).
 *
 * <p>Содержит поля, которых нет в ленте-списке и ради которых делается отдельный
 * запрос детали: локация, сырая строка зарплаты (для показа/хранения как есть) и
 * структурированный зарплатный диапазон {@link CompensationRange} (сырьё для
 * нормализации, §6). Отсутствующее поле — {@code null} (явное «неизвестно»).</p>
 *
 * @param rawLocation     сырая локация или {@code null}
 * @param rawCompensation сырая строка зарплаты для показа или {@code null}
 * @param compensation    структурированный зарплатный диапазон или {@code null}
 */
public record FetchedPosting(String rawLocation, String rawCompensation, CompensationRange compensation) {
}
