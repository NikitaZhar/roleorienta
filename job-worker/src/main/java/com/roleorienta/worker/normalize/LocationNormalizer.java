package com.roleorienta.worker.normalize;

import com.roleorienta.core.domain.WorkModality;
import org.springframework.stereotype.Component;

/**
 * Нормализатор локации (§6): приводит свободную строку локации источника к
 * {@link NormalizedLocation}, соблюдая правило «неизвестное — явно, без догадок».
 *
 * <p>В отличие от зарплаты, Greenhouse отдаёт локацию только свободной строкой
 * ({@code location.name}), без структурированных город/страна/формат. Поэтому разбор
 * эвристический и намеренно осторожный: формат работы берётся лишь по явным словам
 * ({@code remote}/{@code hybrid}); их отсутствие даёт {@link WorkModality#UNKNOWN},
 * а не «офис». Город и страна разбираются лишь для обычного места (модальность
 * {@code UNKNOWN}), чтобы строки вроде {@code "Remote, EU"} не превращались в
 * город={@code Remote}. Логика вынесена в отдельный компонент, чтобы правила были в
 * одном месте, как у {@link SalaryNormalizer}.</p>
 */
@Component
public class LocationNormalizer {

    /** Разделитель «город, страна» в свободной строке локации. */
    private static final String CITY_COUNTRY_SEPARATOR = ",";

    /**
     * @param rawLocation свободная строка локации из адаптера или {@code null}
     * @return нормализованная локация ({@link NormalizedLocation#ABSENT}, если строки нет)
     */
    public NormalizedLocation normalize(String rawLocation) {
        if (rawLocation == null || rawLocation.isBlank()) {
            return NormalizedLocation.ABSENT;
        }
        String value = rawLocation.strip();
        WorkModality modality = detectModality(value);
        if (modality != WorkModality.UNKNOWN) {
            // remote/hybrid: город/страна из строки не разбираем (иначе «Remote, EU»
            // дал бы город=Remote) — оставляем явное «неизвестно».
            return new NormalizedLocation(null, null, modality);
        }
        return splitCityCountry(value);
    }

    /**
     * Определяет формат работы по явным словам в строке: {@code remote} → REMOTE,
     * иначе {@code hybrid} → HYBRID, иначе UNKNOWN (отсутствие слова ≠ офис).
     */
    private WorkModality detectModality(String value) {
        String lower = value.toLowerCase();
        if (lower.contains("remote")) {
            return WorkModality.REMOTE;
        }
        if (lower.contains("hybrid")) {
            return WorkModality.HYBRID;
        }
        return WorkModality.UNKNOWN;
    }

    /**
     * Разбирает обычное место вида «City, Country»: делит по последней запятой на
     * город (слева) и страну (справа). Без запятой — вся строка считается городом,
     * страна {@code null}. Модальность остаётся {@link WorkModality#UNKNOWN}.
     */
    private NormalizedLocation splitCityCountry(String value) {
        int separator = value.lastIndexOf(CITY_COUNTRY_SEPARATOR);
        if (separator < 0) {
            return new NormalizedLocation(value, null, WorkModality.UNKNOWN);
        }
        String city = value.substring(0, separator).strip();
        String country = value.substring(separator + 1).strip();
        return new NormalizedLocation(
                city.isEmpty() ? null : city,
                country.isEmpty() ? null : country,
                WorkModality.UNKNOWN);
    }
}
