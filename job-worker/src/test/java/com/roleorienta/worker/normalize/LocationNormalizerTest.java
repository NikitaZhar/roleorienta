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

    /** Реальные строки Workday со стенда (§64/§65). */
    @Test
    void workdayCityRegionCountryTakesFirstAndLast() {
        NormalizedLocation vienna = normalizer.normalize("Vienna, Vienna, Austria");
        assertThat(vienna.city()).isEqualTo("Vienna");
        assertThat(vienna.country()).isEqualTo("Austria");
        assertThat(normalizer.normalize("Kosice, Kosicky kraj, Slovakia").city()).isEqualTo("Kosice");
        assertThat(normalizer.normalize("Guntramsdorf, Lower Austria, Austria").city()).isEqualTo("Guntramsdorf");
    }

    @Test
    void usStateCodeMeansUnitedStates() {
        NormalizedLocation houston = normalizer.normalize("Houston, TX");
        assertThat(houston.city()).isEqualTo("Houston");
        assertThat(houston.country()).isEqualTo(LocationNormalizer.USA);
        assertThat(normalizer.normalize("Bratislava, SK").country())
                .as("SK — не код штата США").isEqualTo("SK");
    }

    @Test
    void sourceCountryAndRemoteTypeWin() {
        NormalizedLocation hybrid = normalizer.normalize("Vienna, Vienna, Austria", "Austria", "Hybrid");
        assertThat(hybrid.city()).isEqualTo("Vienna");
        assertThat(hybrid.country()).isEqualTo("Austria");
        assertThat(hybrid.modality()).isEqualTo(WorkModality.HYBRID);

        NormalizedLocation remote = normalizer.normalize("Remote - Austria", "Austria", null);
        assertThat(remote.city()).isNull();
        assertThat(remote.country()).as("страну источника сохраняем и для remote").isEqualTo("Austria");
        assertThat(remote.modality()).isEqualTo(WorkModality.REMOTE);

        assertThat(normalizer.normalize("SVK - BL - BRATISLAVA", "Slovakia", null).country()).isEqualTo("Slovakia");
        assertThat(normalizer.normalize("Vienna, Austria", "Austria", null).modality())
                .as("без remoteType и слов — неизвестно, не офис").isEqualTo(WorkModality.UNKNOWN);
    }

    @Test
    void remoteTypeMapping() {
        assertThat(LocationNormalizer.modalityOf("Hybrid")).isEqualTo(WorkModality.HYBRID);
        assertThat(LocationNormalizer.modalityOf("Flex")).isEqualTo(WorkModality.HYBRID);
        assertThat(LocationNormalizer.modalityOf("Fully Remote")).isEqualTo(WorkModality.REMOTE);
        assertThat(LocationNormalizer.modalityOf("On-site")).isEqualTo(WorkModality.ONSITE);
        assertThat(LocationNormalizer.modalityOf("Onsite")).isEqualTo(WorkModality.ONSITE);
        assertThat(LocationNormalizer.modalityOf("Something")).isNull();
        assertThat(LocationNormalizer.modalityOf(null)).isNull();
    }
}
