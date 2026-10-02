package com.roleorienta.worker.source;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Доступ к источникам. {@link JpaRepository} даёт готовые операции (поиск по id, сохранение,
 * список); реализацию создаёт Spring Data. https://docs.spring.io/spring-data/jpa/reference/
 */
public interface SourceRepository extends JpaRepository<Source, Long> {

    /**
     * Источники к чтению — все, кроме источника канала ниже кадровой страницы (государственный портал),
     * если у его компании есть другой источник: у компании читается один канал (бизнес-описание §4.1).
     * {@link Query} с {@code nativeQuery} — запрос на SQL PostgreSQL, Spring Data превращает строки в
     * {@link Source}. https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html
     *
     * @param channelProvider провайдер канала ниже кадровой страницы
     * @return источники к чтению
     */
    @Query(nativeQuery = true, value = """
            SELECT s.* FROM source s
            WHERE s.provider <> ?1
               OR NOT EXISTS (SELECT 1 FROM company_source own
                              JOIN company_source other ON other.company_id = own.company_id
                              JOIN source o ON o.id = other.source_id
                              WHERE own.source_id = s.id AND o.provider <> ?1)
            ORDER BY s.id
            """)
    List<Source> findToRead(String channelProvider);
}
