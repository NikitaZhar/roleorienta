package com.roleorienta.worker.source;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Нужно ли источнику название работодателя от системы найма: компании-работодателя у него нет (доска обратного
     * пути, §27, §34) и название ещё не записано (аудит §65).
     *
     * @param sourceId источник
     * @return {@code true} — запросить название
     */
    @Query(nativeQuery = true, value = """
            SELECT s.employer_name IS NULL AND NOT EXISTS (SELECT 1 FROM company_source cs
                                                           WHERE cs.source_id = s.id AND cs.role = 'EMPLOYER')
            FROM source s WHERE s.id = ?1
            """)
    boolean needsEmployerName(Long sourceId);

    /**
     * Записывает название работодателя источника, если его ещё нет.
     *
     * @param sourceId источник
     * @param name     название, как его даёт система найма
     * @return 1 — записано, 0 — уже было
     */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = "UPDATE source SET employer_name = ?2 WHERE id = ?1 AND employer_name IS NULL")
    int nameEmployer(Long sourceId, String name);
}
