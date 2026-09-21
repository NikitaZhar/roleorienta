package com.roleorienta.api.discovery;

import com.roleorienta.api.discovery.EmployerCandidateDtos.CandidateResponse;
import com.roleorienta.api.discovery.EmployerCandidateDtos.DiscoverRequest;
import com.roleorienta.core.domain.Company;
import com.roleorienta.core.domain.CompanySource;
import com.roleorienta.core.domain.EmployerCandidate;
import com.roleorienta.core.domain.EmployerCandidateState;
import com.roleorienta.core.domain.Provider;
import com.roleorienta.core.domain.ProviderKind;
import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceKind;
import com.roleorienta.core.domain.SourceState;
import com.roleorienta.core.domain.VerifiedBy;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика очереди подтверждения кандидатов в работодатели (§5, ADR-6, §33).
 *
 * <p>Триггер обнаружения ставит задание в outbox (обработает job-worker); список
 * читает очередь; подтверждение заводит {@link Company}, {@link Source} (в состоянии
 * {@code ACTIVE} — планировщик сам поставит его на сбор) и связь
 * {@link CompanySource} (подтверждено человеком, §4), отклонение помечает кандидата.
 * Контроллер тонкий — вся логика здесь (§3.3).</p>
 */
@Service
public class EmployerDiscoveryService {

    private final EmployerCandidateRepository candidates;
    private final ProviderRepository providers;
    private final CompanyRepository companies;
    private final SourceRepository sources;
    private final CompanySourceRepository companySources;
    private final DiscoveryJobEnqueuer enqueuer;

    public EmployerDiscoveryService(
            EmployerCandidateRepository candidates,
            ProviderRepository providers,
            CompanyRepository companies,
            SourceRepository sources,
            CompanySourceRepository companySources,
            DiscoveryJobEnqueuer enqueuer) {
        this.candidates = candidates;
        this.providers = providers;
        this.companies = companies;
        this.sources = sources;
        this.companySources = companySources;
        this.enqueuer = enqueuer;
    }

    /** Ставит обнаружение по явному кандидату (обработает job-worker). */
    public void discover(DiscoverRequest request) {
        enqueuer.enqueueDiscoverEmployer(request.providerCode(), request.slug(), request.baseUrl());
    }

    /** Очередь кандидатов, новые сверху. */
    public List<CandidateResponse> list() {
        return candidates.findAllByOrderByIdDesc().stream()
                .map(CandidateResponse::of)
                .toList();
    }

    /**
     * Подтверждает кандидата: заводит компанию, источник (ACTIVE) и связь; переводит
     * кандидата в {@code CONFIRMED} и проставляет ссылки на созданные записи.
     *
     * @param id          идентификатор кандидата
     * @param companyName имя компании (необязательно; по умолчанию — slug)
     * @return обновлённый кандидат
     */
    @Transactional
    public CandidateResponse confirm(Long id, String companyName) {
        EmployerCandidate candidate = requirePending(id);

        Provider provider = providers.findByCode(candidate.getProviderCode())
                .orElseGet(() -> createProvider(candidate.getProviderCode()));

        Company company = new Company();
        company.setName(StringUtils.hasText(companyName) ? companyName : candidate.getSlug());
        companies.save(company);

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

        candidate.setState(EmployerCandidateState.CONFIRMED);
        candidate.setCompanyId(company.getId());
        candidate.setSourceId(source.getId());
        candidates.save(candidate);
        return CandidateResponse.of(candidate);
    }

    /**
     * Отклоняет кандидата.
     *
     * @param id идентификатор кандидата
     * @return обновлённый кандидат
     */
    @Transactional
    public CandidateResponse reject(Long id) {
        EmployerCandidate candidate = requirePending(id);
        candidate.setState(EmployerCandidateState.REJECTED);
        candidates.save(candidate);
        return CandidateResponse.of(candidate);
    }

    private EmployerCandidate requirePending(Long id) {
        EmployerCandidate candidate = candidates.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Кандидат не найден: id=" + id));
        if (candidate.getState() != EmployerCandidateState.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Кандидат уже обработан: " + candidate.getState());
        }
        return candidate;
    }

    private Provider createProvider(String code) {
        Provider provider = new Provider();
        provider.setCode(code);
        provider.setDisplayName(code);
        provider.setKind(ProviderKind.ATS);
        return providers.save(provider);
    }
}
