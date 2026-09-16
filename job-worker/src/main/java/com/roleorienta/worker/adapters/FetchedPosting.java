package com.roleorienta.worker.adapters;

/**
 * Детальные поля публикации, добранные адаптером с detail-endpoint (§5 техдока).
 *
 * <p>Содержит только поля, которых нет в ленте-списке и ради которых делается
 * отдельный запрос детали (в этом срезе — локация и строка зарплаты). Значения
 * сырые, как их отдаёт источник; нормализация (валюта/период/gross-net, языки,
 * §6, A09) — отдельный срез. Отсутствующее поле — {@code null} (явное «неизвестно»,
 * а не догадка).</p>
 *
 * @param rawLocation     сырая локация или {@code null}
 * @param rawCompensation сырая строка зарплаты/компенсации или {@code null}
 */
public record FetchedPosting(String rawLocation, String rawCompensation) {
}
