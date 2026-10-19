package com.roleorienta.worker.vacancy;

import java.util.Collection;
import java.util.List;
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
     * Открытые публикации других источников компаний этого источника (через {@code company_source}, любая роль) у
     * незакрытых вакансий — кандидаты на присоединение новой публикации (§139; один запрос на чтение — аудит §143).
     *
     * @param sourceId источник читаемых публикаций
     * @return строки: id вакансии, ссылка, провайдер, внешний id; по id вакансии
     */
    @Query(value = """
            SELECT p.vacancy_id, p.url, s.provider, p.external_id
            FROM job_posting p JOIN vacancy v ON v.id = p.vacancy_id JOIN source s ON s.id = p.source_id
            WHERE p.source_id IN (SELECT o.source_id FROM company_source o JOIN company_source m
                                  ON m.company_id = o.company_id WHERE m.source_id = :sourceId)
              AND p.source_id <> :sourceId AND p.closed_at IS NULL AND v.state <> 'CLOSED'
            ORDER BY p.vacancy_id""", nativeQuery = true)
    List<Object[]> findNeighbourPostings(@Param("sourceId") long sourceId);

    /**
     * @param sourceId источник
     * @return внешние id публикаций источника, присоединённых к вакансии с более ранней публикацией другого
     *         источника: название и ссылку вакансии они не меняют (аудит §143)
     */
    @Query("select p.externalId from JobPosting p where p.source.id = ?1 and exists "
            + "(select q.id from JobPosting q where q.vacancy = p.vacancy and q.id < p.id)")
    List<String> findJoinedExternalIds(Long sourceId);

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
