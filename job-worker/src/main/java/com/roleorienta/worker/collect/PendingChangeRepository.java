package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.PendingChange;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к журналу {@code pending_change} (§8, A16). Запись — через {@code save} в той
 * же транзакции, что и ревизия. Выборка необработанных для сопоставления — в §39.
 */
public interface PendingChangeRepository extends JpaRepository<PendingChange, Long> {

    /**
     * Необработанные записи журнала (processed_at IS NULL), старые сверху, страницей —
     * вход сопоставления MATCH_SUBSCRIPTIONS (§39). Сериализацию обеспечивает leader-lock,
     * поэтому SKIP LOCKED не нужен.
     *
     * @param pageable ограничение размера пачки
     * @return необработанные записи в порядке возрастания id
     */
    List<PendingChange> findByProcessedAtIsNullOrderById(Pageable pageable);
}
