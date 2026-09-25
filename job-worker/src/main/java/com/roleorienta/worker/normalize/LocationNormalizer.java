package com.roleorienta.worker.normalize;

import com.roleorienta.core.domain.WorkModality;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Нормализатор локации (§6): приводит локацию источника к {@link NormalizedLocation},
 * соблюдая правило «неизвестное — явно, без догадок».
 *
 * <p><b>Свободная строка</b> (Greenhouse — только {@code location.name}): формат работы —
 * лишь по явным словам ({@code remote}/{@code hybrid}), их отсутствие даёт
 * {@link WorkModality#UNKNOWN}, а не «офис». Город и страна разбираются для обычного места:
 * «Город, Страна» и «Город, Регион, Страна» (формат Workday, §65) — город первый сегмент,
 * страна последний; «Город, ST» с кодом штата США — страна «United States of America».
 * Для remote/hybrid-строк город не разбирается («Remote, EU» ≠ город Remote).</p>
 *
 * <p><b>Структурные поля источника</b> (§65, Workday) главнее эвристики: страна основной
 * локации ({@code country.descriptor}) и формат работы ({@code remoteType}: Remote → REMOTE,
 * Hybrid/Flex → HYBRID, On-site → ONSITE). Офис ({@code ONSITE}) ставится только по явному
 * сообщению источника, никогда по отсутствию слова.</p>
 */
@Component
public class LocationNormalizer {

    /** Страна для локаций вида «City, ST» с кодом штата США. */
    static final String USA = "United States of America";

    /** Коды штатов США и округа Колумбия (строго заглавными). */
    private static final Set<String> US_STATES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA",
            "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM",
            "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA",
            "WV", "WI", "WY");

    /**
     * @param rawLocation свободная строка локации из адаптера или {@code null}
     * @return нормализованная локация ({@link NormalizedLocation#ABSENT}, если строки нет)
     */
    public NormalizedLocation normalize(String rawLocation) {
        return normalize(rawLocation, null, null);
    }

    /**
     * Нормализация с учётом структурных полей источника (§65).
     *
     * @param rawLocation      свободная строка локации или {@code null}
     * @param sourceCountry    страна от источника или {@code null}
     * @param sourceRemoteType формат работы от источника как есть или {@code null}
     * @return нормализованная локация
     */
    public NormalizedLocation normalize(String rawLocation, String sourceCountry, String sourceRemoteType) {
        String country = blankToNull(sourceCountry);
        WorkModality structured = modalityOf(sourceRemoteType);
        if (rawLocation == null || rawLocation.isBlank()) {
            if (country == null && structured == null) {
                return NormalizedLocation.ABSENT;
            }
            return new NormalizedLocation(null, country, structured == null ? WorkModality.UNKNOWN : structured);
        }
        String value = rawLocation.strip();
        WorkModality fromText = detectModality(value);
        if (fromText != WorkModality.UNKNOWN) {
            // remote/hybrid в строке: город не разбираем («Remote, EU» ≠ город Remote);
            // страна — только если её сообщил источник.
            return new NormalizedLocation(null, country, structured == null ? fromText : structured);
        }
        NormalizedLocation split = splitCityCountry(value);
        return new NormalizedLocation(
                split.city(),
                country != null ? country : split.country(),
                structured == null ? WorkModality.UNKNOWN : structured);
    }

    /**
     * Формат работы от источника: Remote → REMOTE, Hybrid/Flex → HYBRID, On-site/Onsite/
     * Office → ONSITE; иное или {@code null} → {@code null} (не сообщено).
     */
    static WorkModality modalityOf(String remoteType) {
        if (remoteType == null || remoteType.isBlank()) {
            return null;
        }
        String letters = remoteType.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (letters.contains("remote")) {
            return WorkModality.REMOTE;
        }
        if (letters.contains("hybrid") || letters.startsWith("flex")) {
            return WorkModality.HYBRID;
        }
        if (letters.startsWith("onsite") || letters.equals("office") || letters.equals("inoffice")) {
            return WorkModality.ONSITE;
        }
        return null;
    }

    /**
     * Определяет формат работы по явным словам в строке: {@code remote} → REMOTE,
     * иначе {@code hybrid} → HYBRID, иначе UNKNOWN (отсутствие слова ≠ офис).
     */
    private WorkModality detectModality(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("remote")) {
            return WorkModality.REMOTE;
        }
        if (lower.contains("hybrid")) {
            return WorkModality.HYBRID;
        }
        return WorkModality.UNKNOWN;
    }

    /**
     * Разбирает обычное место: сегменты через запятую; один сегмент — город; иначе город —
     * первый сегмент, страна — последний («Vienna, Vienna, Austria» → Vienna/Austria);
     * последний сегмент — код штата США → страна {@link #USA}.
     */
    private NormalizedLocation splitCityCountry(String value) {
        List<String> parts = Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
        if (parts.isEmpty()) {
            return new NormalizedLocation(null, null, WorkModality.UNKNOWN);
        }
        if (parts.size() == 1) {
            return new NormalizedLocation(codedCity(parts.get(0)), null, WorkModality.UNKNOWN);
        }
        String last = parts.get(parts.size() - 1);
        String country = US_STATES.contains(last) ? USA : last;
        return new NormalizedLocation(parts.get(0), country, WorkModality.UNKNOWN);
    }

    /** Коды стран ISO alpha-3 в начале строки вида «AUT - VIENNA», «SVK - BL - BRATISLAVA». */
    private static final java.util.regex.Pattern CODED = java.util.regex.Pattern.compile("^[A-Z]{3}\\s+-\\s+.+");

    /**
     * Город из строки «КОД - [Регион -] ГОРОД» (формат части тенантов Workday — DXC, Ecolab,
     * §65): последний сегмент через « - »; ЗАГЛАВНЫЕ приводятся к «Bratislava». Иная строка —
     * как есть.
     */
    static String codedCity(String value) {
        if (!CODED.matcher(value).matches()) {
            return value;
        }
        String[] parts = value.split("\\s+-\\s+");
        String city = parts[parts.length - 1].strip();
        if (city.equals(city.toUpperCase(Locale.ROOT))) {
            city = Arrays.stream(city.toLowerCase(Locale.ROOT).split(" "))
                    .map(word -> word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1))
                    .collect(java.util.stream.Collectors.joining(" "));
        }
        return city;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
