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

    /**
     * Подсказка: включённые позиции, в названии или коде которых есть строка, по названию; не больше 20. Условие
     * «включена» повторено в каждой ветви «или»: так Spring Data строит {@code active AND name … OR active AND
     * code …} (выключение — §160).
     *
     * @param name часть названия
     * @param code часть кода
     * @return позиции
     */
    List<Position> findTop20ByActiveTrueAndNameContainingIgnoreCaseOrActiveTrueAndCodeContainingIgnoreCaseOrderByName(
            String name, String code);
}
