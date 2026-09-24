package com.roleorienta.api.discovery;

import com.roleorienta.api.discovery.EmployerCandidateDtos.Link;
import com.roleorienta.core.domain.Company;
import com.roleorienta.core.domain.CompanySource;
import com.roleorienta.core.domain.EmployerCandidate;
import com.roleorienta.core.domain.Provider;
import com.roleorienta.core.domain.ProviderKind;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceKind;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.core.domain.VerifiedBy;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Заводит работодателя по подтверждённому кандидату: {@link Company}, {@link Source} в
 * состоянии {@code ACTIVE} (планировщик сам поставит его на сбор) и связь
 * {@link CompanySource} с отметкой «подтверждено человеком» (§4). Вынесено из
 * {@link EmployerDiscoveryService} (§78): иначе у сервиса 6 зависимостей (контракт §3.10).
 * Вызывается в транзакции сервиса.
 */
@Component
public class EmployerRegistration {

    private final ProviderRepository providers;
    private final CompanyRepository companies;
    private final SourceRepository sources;
    private final CompanySourceRepository companySources;

    /**
     * @param providers      провайдеры (недостающий заводится)
     * @param companies      компании
     * @param sources        источники сбора
     * @param companySources связи компания — источник
     */
    public EmployerRegistration(ProviderRepository providers, CompanyRepository companies,
                                SourceRepository sources, CompanySourceRepository companySources) {
        this.providers = providers;
        this.companies = companies;
        this.sources = sources;
        this.companySources = companySources;
    }

    /**
     * Заводит источник и связь для кандидата; компанию — только если работодатель ещё не
     * известен по ключу идентичности ({@link Company#identityKey}, A3, §80: второй сайт того
     * же тенанта Workday — та же компания).
     *
     * @param candidate   кандидат (провайдер, slug, базовый адрес)
     * @param companyName имя новой компании; пусто — используется slug; у известной компании
     *                    имя не меняется
     * @return идентификаторы заведённых компании и источника
     */
    public Link register(EmployerCandidate candidate, String companyName) {
        Provider provider = providers.findByCode(candidate.getProviderCode())
                .orElseGet(() -> createProvider(candidate.getProviderCode()));

        String identityKey = Company.identityKey(candidate.getProviderCode(), candidate.getSlug());
        Company company = companies.findByIdentityKey(identityKey).orElseGet(() -> {
            Company created = new Company();
            created.setName(StringUtils.hasText(companyName) ? companyName : candidate.getSlug());
            created.setIdentityKey(identityKey);
            return companies.save(created);
        });

        Source source = new Source();
        source.setProvider(provider);
        source.setKind(SourceKind.COMPANY_BOARD);
        source.setExternalRef(candidate.getSlug());
        source.setBaseUrl(candidate.getBaseUrl());
        source.setState(SourceState.ACTIVE);
        sources.save(source);

        CompanySource link = new CompanySource();
        link.setSource(source);
        link.setCompany(company);
        link.setVerifiedBy(VerifiedBy.MANUAL);
        link.setVerifiedAt(Instant.now());
        companySources.save(link);
        return new Link(company.getId(), source.getId());
    }

    private Provider createProvider(String code) {
        Provider provider = new Provider();
        provider.setCode(code);
        provider.setDisplayName(code);
        provider.setKind(ProviderKind.ATS);
        return providers.save(provider);
    }
}
