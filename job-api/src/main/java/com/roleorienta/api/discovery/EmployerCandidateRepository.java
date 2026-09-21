package com.roleorienta.api.discovery;

import com.roleorienta.core.domain.EmployerCandidate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к очереди кандидатов в работодатели для админ-эндпоинтов. */
public interface EmployerCandidateRepository extends JpaRepository<EmployerCandidate, Long> {

    /** Все кандидаты, новые сверху (очередь подтверждения). */
    List<EmployerCandidate> findAllByOrderByIdDesc();
}
