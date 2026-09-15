package com.roleorienta.worker.scheduling;

import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к источникам сбора для планировщика.
 */
public interface SourceRepository extends JpaRepository<Source, Long> {

    /**
     * Возвращает источники в заданном состоянии (планировщик берёт {@code ACTIVE}).
     *
     * @param state состояние источника
     * @return список источников в этом состоянии
     */
    List<Source> findByState(SourceState state);
}
