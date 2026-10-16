package com.roleorienta.worker.adapter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Даты размещения публикаций работодателем, собранные адаптером при чтении списка (§127): внешний id → дата. Дата
 * хранится днём (началом суток UTC): источники дают то дату, то момент с разными записями смещения, а на экране нужна
 * дата. Первая записанная дата публикации остаётся.
 */
public final class PostedDates {

    /** Дата в начале значения: {@code 2026-10-13}, {@code 2026-10-15T06:04:23+00:00}, {@code …T17:56:51.721+0000}. */
    private static final Pattern ISO_DATE = Pattern.compile("^\\s*(\\d{4}-\\d{2}-\\d{2})");

    private final Map<String, Instant> byExternalId = new HashMap<>();

    /**
     * @param externalId внешний id публикации
     * @param postedAt   дата размещения; {@code null} — не записывается
     */
    public void put(String externalId, Instant postedAt) {
        if (postedAt != null) {
            byExternalId.putIfAbsent(externalId, postedAt);
        }
    }

    /**
     * @param result результат чтения
     * @return тот же результат; прочитанный — с собранными датами
     */
    public SourceReadResult attach(SourceReadResult result) {
        return result instanceof SourceReadResult.Read read ? read.withPostedAt(Map.copyOf(byExternalId)) : result;
    }

    /**
     * Дата из начала ISO-значения (дата или момент в любой записи смещения).
     *
     * @param text значение источника; {@code null} или без даты в начале — нет даты
     * @return начало суток UTC; {@code null} — даты нет
     */
    public static Instant parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = ISO_DATE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return day(LocalDate.parse(matcher.group(1)));
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    /**
     * @param date дата
     * @return начало суток UTC
     */
    public static Instant day(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
