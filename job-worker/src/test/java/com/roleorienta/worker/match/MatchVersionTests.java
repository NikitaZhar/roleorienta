package com.roleorienta.worker.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Длина версии сопоставления (аудит §154): версия хранится в колонках {@code VARCHAR(40)}, а в тестах на БД
 * справочник городов пуст и версия короче рабочей — превышение ловил только стенд (стенограмма §149). Здесь версия
 * собирается из настоящих регионов и слов языков (§158) при номере версии словаря позиций (§159) и справочнике городов
 * с запасом.
 */
class MatchVersionTests {

    /** Справочник GeoNames cities15000 — около 34 тыс. названий; запас — на порядок больше. */
    private static final int CITIES_WITH_MARGIN = 999_999;
    /** Номер версии словаря позиций с запасом: растёт с каждой правкой администратора (§159). */
    private static final int DICTIONARY_EDITS = 999_999;

    /**
     * Рабочая версия с запасом по числу городов укладывается в колонки БД.
     */
    @Test
    void versionFitsDatabaseColumns() {
        String languages = new LanguageRequirements().version();
        String version = MatchVacanciesHandler.version(MatchRepository.VERSION_PREFIX + DICTIONARY_EDITS,
                new RemoteRegions().version(), languages, CITIES_WITH_MARGIN);

        assertThat(version).hasSizeLessThanOrEqualTo(MatchVacanciesHandler.MAX_VERSION_LENGTH)
                .endsWith("/g" + CITIES_WITH_MARGIN + "/" + LocationResolver.VERSION + "/" + languages);
    }

    /**
     * Слишком длинная версия — понятная ошибка, а не отказ записи в БД.
     */
    @Test
    void tooLongVersionIsRejected() {
        assertThatThrownBy(() -> MatchVacanciesHandler.version("d1-very-long-dictionary-version", "2026-09-29.1", "l1", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("longer than " + MatchVacanciesHandler.MAX_VERSION_LENGTH);
    }
}
