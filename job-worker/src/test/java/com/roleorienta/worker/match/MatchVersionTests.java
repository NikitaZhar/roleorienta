package com.roleorienta.worker.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Длина версии сопоставления (аудит §154): версия хранится в колонках {@code VARCHAR(40)}, а в тестах на БД
 * справочник городов пуст и версия короче рабочей — превышение ловил только стенд (стенограмма §149). Здесь версия
 * собирается из настоящих словаря позиций и регионов при справочнике городов с запасом.
 */
class MatchVersionTests {

    /** Справочник GeoNames cities15000 — около 34 тыс. названий; запас — на порядок больше. */
    private static final int CITIES_WITH_MARGIN = 999_999;

    /**
     * Рабочая версия с запасом по числу городов укладывается в колонки БД.
     */
    @Test
    void versionFitsDatabaseColumns() {
        String version = MatchVacanciesHandler.version(new PositionDictionary().version(),
                new RemoteRegions().version(), CITIES_WITH_MARGIN);

        assertThat(version).hasSizeLessThanOrEqualTo(MatchVacanciesHandler.MAX_VERSION_LENGTH)
                .endsWith("/g" + CITIES_WITH_MARGIN + "/" + LocationResolver.VERSION);
    }

    /**
     * Слишком длинная версия — понятная ошибка, а не отказ записи в БД.
     */
    @Test
    void tooLongVersionIsRejected() {
        assertThatThrownBy(() -> MatchVacanciesHandler.version("2026-10-16.1-very-long", "2026-09-29.1", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("longer than " + MatchVacanciesHandler.MAX_VERSION_LENGTH);
    }
}
