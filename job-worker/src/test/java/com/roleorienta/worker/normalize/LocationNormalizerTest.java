package com.roleorienta.worker.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.core.domain.WorkModality;
import org.junit.jupiter.api.Test;

/**
 * Модульные тесты нормализации локации (§6, A01). Проверяют правило «формат работы —
 * только по явным словам, иначе UNKNOWN» и разбор «City, Country» лишь для обычного
 * места (не remote/hybrid).
 */
class LocationNormalizerTest {

    private final LocationNormalizer normalizer = new LocationNormalizer();

    @Test
    void cityAndCountrySplitForOrdinaryPlace() {
        NormalizedLocation location = normalizer.normalize("Berlin, Germany");
        assertThat(location.city()).isEqualTo("Berlin");
        assertThat(location.country()).isEqualTo("Germany");
        assertThat(location.modality()).isEqualTo(WorkModality.UNKNOWN);
    }

    @Test
    void remoteKeepsCityAndCountryNull() {
        NormalizedLocation location = normalizer.normalize("Remote, EU");
        assertThat(location.city()).isNull();
        assertThat(location.country()).isNull();
        assertThat(location.modality()).isEqualTo(WorkModality.REMOTE);
    }

    @Test
    void hybridDetectedByExplicitWord() {
        assertThat(normalizer.normalize("Hybrid, Berlin").modality())
                .isEqualTo(WorkModality.HYBRID);
    }

    @Test
    void cityWithoutCommaHasNoCountry() {
        NormalizedLocation location = normalizer.normalize("Munich");
        assertThat(location.city()).isEqualTo("Munich");
        assertThat(location.country()).isNull();
        assertThat(location.modality()).isEqualTo(WorkModality.UNKNOWN);
    }

    @Test
    void blankOrNullIsAbsent() {
        assertThat(normalizer.normalize(null)).isEqualTo(NormalizedLocation.ABSENT);
        assertThat(normalizer.normalize("   ")).isEqualTo(NormalizedLocation.ABSENT);
    }
}
