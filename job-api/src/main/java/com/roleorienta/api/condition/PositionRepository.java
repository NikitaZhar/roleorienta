package com.roleorienta.api.condition;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Позиции словаря.
 */
public interface PositionRepository extends JpaRepository<Position, Long> {

    /**
     * @param codes коды позиций
     * @return найденные позиции (неизвестных кодов среди них нет)
     */
    List<Position> findByCodeIn(Collection<String> codes);
}
