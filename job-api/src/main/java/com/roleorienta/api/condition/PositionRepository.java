package com.roleorienta.api.condition;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Позиции словаря.
 */
public interface PositionRepository extends JpaRepository<Position, Long> {

    /**
     * @param code код позиции
     * @return позиция
     */
    Optional<Position> findByCode(String code);

    /**
     * Подсказка: позиции, в названии или коде которых есть строка, по названию; не больше 20.
     *
     * @param name часть названия
     * @param code часть кода
     * @return позиции
     */
    List<Position> findTop20ByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrderByName(String name, String code);
}
