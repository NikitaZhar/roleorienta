package com.roleorienta.worker.discovery;

import com.roleorienta.core.domain.Company;
import com.roleorienta.core.domain.CompanySource;
import com.roleorienta.core.domain.Provider;
import com.roleorienta.core.domain.ProviderKind;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceKind;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.core.domain.VerifiedBy;
import com.roleorienta.worker.scheduling.SourceRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Заводит реальный источник сбора из уверенного кандидата обнаружения (§5, ADR-6, §35).
 *
 * <p>Материализует связку {@link Provider} (находит по коду или создаёт),
 * {@link Company}, {@link Source} в состоянии {@code ACTIVE} (планировщик §6 сам
 * поставит его на сбор) и {@link CompanySource} с {@code verifiedBy=AUTO} — это и
 * есть авто-подключение по гейту уверенности. Логика повторяет ручное подтверждение
 * в job-api ({@code EmployerDiscoveryService.confirm}); дублирование намеренное —
 * job-api и job-worker разные приложения со своими репозиториями над общими
 * сущностями core.</p>
 *
 * <p>Дедуп (§5): если источник для пары (провайдер, slug) уже есть, он
 * переиспользуется, а не создаётся заново. Вызов идёт внутри транзакции обработчика.</p>
 */
@Component
public class EmployerSourceRegistrar {

    private final ProviderRepository providers;
    private final CompanyRepository companies;
    private final SourceRepository sources;
    private final CompanySourceRepository companySources;

    public EmployerSourceRegistrar(
            ProviderRepository providers,
            CompanyRepository companies,
            SourceRepository sources,
            CompanySourceRepository companySources) {
        this.providers = providers;
        this.companies = companies;
        this.sources = sources;
        this.companySources = companySources;
    }

    /**
     * Заводит (или переиспользует) источник для кандидата.
     *
     * @param providerCode код системы найма
     * @param slug         идентификатор доски у провайдера
     * @param baseUrl      базовый адрес ленты
     * @param companyName  имя компании (в обнаружении — обычно slug, имя из ленты
     *                     не всегда доступно)
     * @return идентификаторы заведённых компании и источника
     */
    public Registration register(String providerCode, String slug, String baseUrl, String companyName) {
        Optional<Source> existing = sources.findByProvider_CodeAndExternalRef(providerCode, slug);
        if (existing.isPresent()) {
            return new Registration(null, existing.get().getId());
        }

        Provider provider = providers.findByCode(providerCode)
                .orElseGet(() -> createProvider(providerCode));

        Company company = new Company();
        company.setName(companyName);
        companies.save(company);

        Source source = new Source();
        source.setProvider(provider);
        source.setKind(SourceKind.COMPANY_BOARD);
        source.setExternalRef(slug);
        source.setBaseUrl(baseUrl);
        source.setState(SourceState.ACTIVE);
        sources.save(source);

        CompanySource link = new CompanySource();
        link.setSource(source);
        link.setCompany(company);
        link.setVerifiedBy(VerifiedBy.AUTO);
        link.setVerifiedAt(Instant.now());
        companySources.save(link);

        return new Registration(company.getId(), source.getId());
    }

    private Provider createProvider(String code) {
        Provider provider = new Provider();
        provider.setCode(code);
        provider.setDisplayName(code);
        provider.setKind(ProviderKind.ATS);
        return providers.save(provider);
    }

    /**
     * Результат авто-подключения: идентификаторы компании и источника. При дедупе
     * (источник уже существовал) {@code companyId} не заполняется.
     */
    public record Registration(Long companyId, Long sourceId) {
    }
}
