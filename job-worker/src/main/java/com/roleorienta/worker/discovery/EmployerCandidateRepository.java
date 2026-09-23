package com.roleorienta.worker.discovery;

import com.roleorienta.core.domain.EmployerCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к очереди кандидатов в работодатели для слоя обнаружения.
 */
public interface EmployerCandidateRepository extends JpaRepository<EmployerCandidate, Long> {

    /**
     * Есть ли уже кандидат с такой парой (провайдер, slug) — дедуп повторных
     * запусков обнаружения (§5): один и тот же работодатель не заводится дважды.
     *
     * @param providerCode код системы найма
     * @param slug         идентификатор доски у провайдера
     * @return {@code true}, если кандидат уже существует
     */
    boolean existsByProviderCodeAndSlug(String providerCode, String slug);

    /**
     * То же без учёта регистра slug — для входов, где провайдер к регистру slug не
     * чувствителен (Workday cxs: {@code acme/External} = {@code acme/external}, §53/§55).
     *
     * @param providerCode код системы найма
     * @param slug         идентификатор доски у провайдера
     * @return {@code true}, если кандидат уже существует в любом регистре
     */
    boolean existsByProviderCodeAndSlugIgnoreCase(String providerCode, String slug);
}
