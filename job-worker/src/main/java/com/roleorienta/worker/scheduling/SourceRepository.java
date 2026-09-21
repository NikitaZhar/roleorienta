package com.roleorienta.worker.scheduling;

import com.roleorienta.core.domain.Source;
import com.roleorienta.core.domain.SourceState;
import java.util.Optional;
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

    /**
     * Ищет источник по коду провайдера и внешнему идентификатору доски (slug) —
     * дедуп при авто-подключении обнаружения (§5): один и тот же источник не
     * заводится дважды.
     *
     * @param providerCode код провайдера
     * @param externalRef  идентификатор доски у провайдера (slug)
     * @return источник, если уже существует
     */
    Optional<Source> findByProvider_CodeAndExternalRef(String providerCode, String externalRef);
}
