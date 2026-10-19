package com.roleorienta.worker.vacancy;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к публикациям. Запросы Spring Data строит по имени метода; {@link Query} — запрос JPQL
 * явно. {@link EntityGraph} загружает вакансии публикаций тем же запросом: запись чтения
 * обращается к вакансии каждой публикации, без этого был бы отдельный запрос на каждую (N+1).
 */
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    /**
     * Ссылка без схемы и {@code www.}, в нижнем регистре — как {@link SameVacancy#address}. Знака {@code ?} в
     * выражении нет: в запросе он был бы параметром.
     */
    String ADDRESS = "regexp_replace(lower(p.url), '^https{0,1}://(www\\.){0,1}', '')";

    /**
     * Незакрытая вакансия той же компании из другого её источника, к которой присоединяется новая публикация
     * (§139; технический документ §16.14): ссылка совпадает без схемы, {@code www.} и {@code /} в конце, или тот же
     * провайдер, тот же хост ссылки и тот же внешний id (языковые разделы одной доски).
     *
     * @param sourceId   источник новой публикации
     * @param address    её ссылка без схемы, {@code www.} и {@code /} в конце
     * @param hostPrefix хост её ссылки и {@code /}
     * @param provider   провайдер источника
     * @param externalId внешний id
     * @return вакансия с наименьшим id; нет — пусто
     */
    @Query(value = "SELECT p.vacancy_id FROM job_posting p JOIN vacancy v ON v.id = p.vacancy_id "
            + "JOIN source s ON s.id = p.source_id "
            + "WHERE p.source_id IN (SELECT o.source_id FROM company_source o JOIN company_source m "
            + "ON m.company_id = o.company_id WHERE m.source_id = :sourceId) "
            + "AND p.source_id <> :sourceId AND p.closed_at IS NULL AND v.state <> 'CLOSED' "
            + "AND (regexp_replace(" + ADDRESS + ", '/+$', '') = :address "
            + "OR (s.provider = :provider AND p.external_id = :externalId AND starts_with(" + ADDRESS
            + ", :hostPrefix))) ORDER BY p.vacancy_id LIMIT 1", nativeQuery = true)
    Optional<Long> findSameVacancy(@Param("sourceId") long sourceId, @Param("address") String address,
            @Param("hostPrefix") String hostPrefix, @Param("provider") String provider,
            @Param("externalId") String externalId);

    /**
     * @param sourceId источник
     * @return все публикации источника
     */
    @EntityGraph(attributePaths = "vacancy")
    List<JobPosting> findBySourceId(Long sourceId);

    /**
     * @param vacancyIds вакансии
     * @return все публикации этих вакансий
     */
    @EntityGraph(attributePaths = "vacancy")
    List<JobPosting> findByVacancyIdIn(Collection<Long> vacancyIds);

    /**
     * @param sourceId источник
     * @return внешние id публикаций источника, текст которых уже получен
     */
    @Query("select p.externalId from JobPosting p where p.source.id = ?1 and p.content is not null")
    List<String> findExternalIdsWithContent(Long sourceId);

    /**
     * @param sourceId источник
     * @return внешние id публикаций источника, у вакансии которых есть дата размещения работодателем (§128)
     */
    @Query("select p.externalId from JobPosting p where p.source.id = ?1 and p.vacancy.postedAt is not null")
    List<String> findExternalIdsWithPostedDate(Long sourceId);

    /**
     * @param sourceId источник
     * @return внешние id незакрытых публикаций источника
     */
    @Query("select p.externalId from JobPosting p where p.source.id = ?1 and p.closedAt is null")
    List<String> findOpenExternalIds(Long sourceId);
}
