package com.roleorienta.api.discovery;

import com.roleorienta.api.discovery.EmployerCandidateDtos.CandidateResponse;
import com.roleorienta.api.discovery.EmployerCandidateDtos.DiscoverRequest;
import com.roleorienta.api.discovery.EmployerCandidateDtos.Link;
import com.roleorienta.core.domain.EmployerCandidate;
import com.roleorienta.core.domain.EmployerCandidateState;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Логика очереди подтверждения кандидатов в работодатели (§5, ADR-6, §33).
 *
 * <p>Триггер обнаружения ставит задание в outbox (обработает job-worker); список
 * читает очередь; подтверждение заводит компанию, источник ({@code ACTIVE}) и связь через
 * {@link EmployerRegistration} (подтверждено человеком, §4), отклонение помечает кандидата.
 * Контроллер тонкий — вся логика здесь (§3.3).</p>
 */
@Service
public class EmployerDiscoveryService {

    private final EmployerCandidateRepository candidates;
    private final EmployerRegistration registration;
    private final DiscoveryJobEnqueuer enqueuer;

    /**
     * @param candidates   очередь кандидатов
     * @param registration заведение компании, источника и связи при подтверждении
     * @param enqueuer     постановка задания обнаружения
     */
    public EmployerDiscoveryService(EmployerCandidateRepository candidates, EmployerRegistration registration,
                                    DiscoveryJobEnqueuer enqueuer) {
        this.candidates = candidates;
        this.registration = registration;
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
        Link link = registration.register(candidate, companyName);
        candidate.setState(EmployerCandidateState.CONFIRMED);
        candidate.setCompanyId(link.companyId());
        candidate.setSourceId(link.sourceId());
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
}
