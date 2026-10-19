package com.roleorienta.worker.site;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Площадки продажи доменов (§135): домен, который компания больше не держит, выставлен на продажу и переадресует на
 * площадку (hugedomains.com, sedo.com …). Такой адрес — не сайт компании: проверка сайта ({@link SiteVerifier})
 * считает его несуществующим, проверка кадровой страницы отклоняет найденный раньше сайт. Список площадок —
 * {@code app.site-check.domain-marketplaces}.
 */
@Component
public class DomainMarketplaces {

    private final List<String> domains;

    /**
     * @param properties список площадок
     */
    public DomainMarketplaces(SiteCheckProperties properties) {
        this.domains = properties.domainMarketplaces().stream().map(DomainMarketplaces::normalized).toList();
    }

    /**
     * @param host хост (конечный после переадресаций); {@code null} — нет
     * @return {@code true} — хост площадки или её поддомен
     */
    public boolean forSale(String host) {
        if (host == null) {
            return false;
        }
        String normalized = normalized(host);
        return domains.stream().anyMatch(domain -> normalized.equals(domain) || normalized.endsWith("." + domain));
    }

    private static String normalized(String host) {
        String lower = host.strip().toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }
}
