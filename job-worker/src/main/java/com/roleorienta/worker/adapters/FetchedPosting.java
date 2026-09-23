package com.roleorienta.worker.adapters;

/**
 * Детальные поля публикации, добранные адаптером с detail-endpoint (§5 техдока).
 *
 * <p>Содержит поля, которых нет в ленте-списке и ради которых делается отдельный
 * запрос детали: локация, сырая строка зарплаты (для показа/хранения как есть),
 * структурированный зарплатный диапазон {@link CompensationRange} (сырьё для
 * нормализации, §6) и текст описания (снятый из HTML источника — сырьё для
 * извлечения требований и языков, §6). Отсутствующее поле — {@code null} (явное
 * «неизвестно»).</p>
 *
 * <p>Структурные поля, которые сообщают не все источники (§65, Workday): страна
 * основной локации, формат работы, дата публикации, прочие локации. {@code null} / пустой
 * список — источник не сообщает; тогда нормализатор разбирает свободную строку.</p>
 *
 * @param rawLocation         сырая локация или {@code null}
 * @param rawCompensation     сырая строка зарплаты для показа или {@code null}
 * @param compensation        структурированный зарплатный диапазон или {@code null}
 * @param rawDescription      текст описания вакансии или {@code null}
 * @param country             страна основной локации от источника (по-английски) или {@code null}
 * @param remoteType          формат работы от источника как есть (напр. {@code Hybrid}) или {@code null}
 * @param postedOn            дата публикации по данным источника или {@code null}
 * @param additionalLocations прочие локации многолокационной вакансии (может быть пустым)
 */
public record FetchedPosting(String rawLocation, String rawCompensation,
                             CompensationRange compensation, String rawDescription,
                             String country, String remoteType, java.time.LocalDate postedOn,
                             java.util.List<String> additionalLocations) {

    public FetchedPosting {
        additionalLocations = additionalLocations == null ? java.util.List.of() : java.util.List.copyOf(additionalLocations);
    }

    /**
     * Деталь без структурных полей (источник их не сообщает — Greenhouse).
     *
     * @param rawLocation     сырая локация или {@code null}
     * @param rawCompensation сырая строка зарплаты или {@code null}
     * @param compensation    структурированный зарплатный диапазон или {@code null}
     * @param rawDescription  текст описания или {@code null}
     */
    public FetchedPosting(String rawLocation, String rawCompensation,
                          CompensationRange compensation, String rawDescription) {
        this(rawLocation, rawCompensation, compensation, rawDescription, null, null, null, java.util.List.of());
    }
}
