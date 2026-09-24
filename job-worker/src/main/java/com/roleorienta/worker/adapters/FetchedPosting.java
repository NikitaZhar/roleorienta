package com.roleorienta.worker.adapters;

import java.time.LocalDate;
import java.util.List;

/**
 * Детальные поля публикации, добранные адаптером с detail-endpoint (§5 техдока).
 *
 * <p>Содержит то, чего нет в ленте-списке и ради чего делается отдельный запрос детали:
 * локация, зарплата, текст описания (снятый из HTML источника — сырьё для извлечения
 * требований, §6), дата публикации. Отсутствующее поле — {@code null} (явное «неизвестно»).
 * Поля сгруппированы (§78): у каждой записи не больше 5 полей (контракт §3.10).</p>
 *
 * @param location    локация как её сообщил источник
 * @param pay         зарплата как её сообщил источник
 * @param description текст описания вакансии или {@code null}
 * @param postedOn    дата публикации по данным источника или {@code null}
 */
public record FetchedPosting(SourceLocation location, SourcePay pay, String description, LocalDate postedOn) {

    /**
     * Локация от источника. Структурные поля сообщают не все источники (§65, Workday):
     * {@code null} / пустой список — источник не сообщает, тогда нормализатор разбирает строку.
     *
     * @param raw        строка локации или {@code null}
     * @param country    страна основной локации (по-английски) или {@code null}
     * @param remoteType формат работы как есть (напр. {@code Hybrid}) или {@code null}
     * @param additional прочие локации многолокационной вакансии (может быть пустым)
     */
    public record SourceLocation(String raw, String country, String remoteType, List<String> additional) {

        public SourceLocation {
            additional = additional == null ? List.of() : List.copyOf(additional);
        }

        /**
         * Только строка локации — источник структурных полей не сообщает (Greenhouse).
         *
         * @param raw строка локации или {@code null}
         * @return локация без структурных полей
         */
        public static SourceLocation of(String raw) {
            return new SourceLocation(raw, null, null, List.of());
        }
    }

    /**
     * Зарплата от источника.
     *
     * @param raw   строка зарплаты для показа или {@code null}
     * @param range структурированный диапазон (сырьё для нормализации, §6) или {@code null}
     */
    public record SourcePay(String raw, CompensationRange range) {

        /** Источник зарплату не сообщил. */
        public static final SourcePay NONE = new SourcePay(null, null);
    }
}
