package com.roleorienta.worker.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Бренд доски по имени сайта (§86): только когда он отличается от компании и тенанта. */
class BoardBrandTest {

    @Test
    void siteOfAnotherBrandGivesBrand() {
        assertEquals(Optional.of("Avanade"), BoardBrand.of("accenture/AvanadeCareers", "Accenture"));
        assertEquals(Optional.of("Michael Kors"), BoardBrand.of("capri/Michael_Kors", "Capri Holdings"));
        assertEquals(Optional.of("Amentum"), BoardBrand.of("pae/Amentum_Careers", "PAE"));
        assertEquals(Optional.of("Banter"), BoardBrand.of("signetjewelers/Banter", "Signet Jewelers"));
    }

    @Test
    void genericOrSameNameSiteGivesNothing() {
        assertEquals(Optional.empty(), BoardBrand.of("hitachi/External", "Hitachi"));
        assertEquals(Optional.empty(), BoardBrand.of("lilly/LLY", "Eli Lilly"));
        assertEquals(Optional.empty(), BoardBrand.of("dxctechnology/DXCJobs", "DXC Technology"));
        assertEquals(Optional.empty(), BoardBrand.of("mmc/MMC", "Marsh McLennan"));
        assertEquals(Optional.empty(), BoardBrand.of("pfizer/PfizerCareers", "Pfizer"));
        assertEquals(Optional.empty(), BoardBrand.of("acme/Search_EU", "Acme"));
        assertEquals(Optional.empty(), BoardBrand.of("acme/External_Career_Site_2", "Acme"));
        assertEquals(Optional.empty(), BoardBrand.of("acme", "Acme"));
    }
}
