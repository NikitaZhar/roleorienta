package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.PendingChange;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к журналу {@code pending_change} (§8, A16). Запись — через {@code save} в той
 * же транзакции, что и ревизия. Выборка необработанных для сопоставления — в §39.
 */
public interface PendingChangeRepository extends JpaRepository<PendingChange, Long> {
}
