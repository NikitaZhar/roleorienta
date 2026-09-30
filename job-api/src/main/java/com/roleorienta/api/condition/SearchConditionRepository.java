package com.roleorienta.api.condition;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Версии условий поиска.
 */
public interface SearchConditionRepository extends JpaRepository<SearchCondition, Long> {

    /**
     * @param userId пользователь
     * @return активная версия
     */
    Optional<SearchCondition> findByUserIdAndActiveTrue(long userId);

    /**
     * Активная версия с блокировкой строки до конца транзакции ({@code PESSIMISTIC_WRITE} — на
     * PostgreSQL {@code FOR NO KEY UPDATE}; несовместима с {@code FOR UPDATE} прохода выдачи в
     * job-worker, поэтому смена условий и порция не идут одновременно).
     *
     * @param userId пользователь
     * @return активная версия
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SearchCondition c where c.userId = ?1 and c.active = true")
    Optional<SearchCondition> findActiveForUpdate(long userId);
}
