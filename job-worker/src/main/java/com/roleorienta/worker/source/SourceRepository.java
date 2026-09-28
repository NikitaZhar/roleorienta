package com.roleorienta.worker.source;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к источникам. {@link JpaRepository} даёт готовые операции (поиск по id, сохранение,
 * список); реализацию создаёт Spring Data. https://docs.spring.io/spring-data/jpa/reference/
 */
public interface SourceRepository extends JpaRepository<Source, Long> {
}
