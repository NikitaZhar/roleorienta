package com.roleorienta.worker.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Площадки продажи доменов (§135): хост площадки и её поддомены — да, похожие имена и прочие сайты — нет.
 */
class DomainMarketplacesTests {

    private final DomainMarketplaces marketplaces = new DomainMarketplaces(
            new SiteCheckProperties("https://{host}/", List.of("hugedomains.com", "Sedo.com")));

    @Test
    void recognizesMarketplaceHostsAndSubdomains() {
        assertThat(marketplaces.forSale("hugedomains.com")).isTrue();
        assertThat(marketplaces.forSale("www.HugeDomains.com")).isTrue();
        assertThat(marketplaces.forSale("sedo.com.")).isTrue();
    }

    @Test
    void otherHostsAreNotMarketplaces() {
        assertThat(marketplaces.forSale("nothugedomains.com")).isFalse();
        assertThat(marketplaces.forSale("hugedomains.com.sk")).isFalse();
        assertThat(marketplaces.forSale("agel.sk")).isFalse();
        assertThat(marketplaces.forSale(null)).isFalse();
    }
}
