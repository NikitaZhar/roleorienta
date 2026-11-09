package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.lock.PostgresLeaderLock;
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
     * Разделяемая блокировка записи вакансий ({@link PostgresLeaderLock#VACANCY_WRITE}, §271): запись чтения
     * источника не идёт одновременно со склейкой вакансий. Первым запросом транзакции записи.
     *
     * @return 1
     */
    @Query(value = PostgresLeaderLock.VACANCY_WRITE_SHARED, nativeQuery = true)
    int lockVacancyWriteShared();

    /**
     * Открытые публикации других источников работодателя этого источника у незакрытых вакансий — кандидаты на
     * присоединение новой публикации (§139, §260; один запрос на чтение — аудит §143). Работодатель — компании
     * источника ({@code company_source}, любая роль), у источника без компании — источники без компании с тем же
     * названием работодателя ({@code source.employer_name} по {@code same_title}: источник портала вне реестра и источник
     * profesia.sk, §259). Ключ названия ({@code same_title}, V92) — только если оба источника не кадровый сайт группы
     * (роль {@code GROUP}: доска группы — вакансии многих стран и компаний), ключ места — {@code same_title} места.
     *
     * @param sourceId источник читаемых публикаций
     * @return строки: id вакансии, ссылка, провайдер, внешний id, ключ названия ({@code null} — не сравнивается),
     *         ключ места (пусто — места нет); по id вакансии
     */
    @Query(value = """
            SELECT p.vacancy_id, p.url, s.provider, p.external_id,
                   CASE WHEN n.by_title THEN same_title(p.title) END AS title_key,
                   same_title(coalesce(p.location, '')) AS place_key
            FROM job_posting p JOIN vacancy v ON v.id = p.vacancy_id JOIN source s ON s.id = p.source_id
            JOIN (SELECT o.source_id, bool_or(o.role <> 'GROUP' AND m.role <> 'GROUP') AS by_title
                  FROM company_source o JOIN company_source m ON m.company_id = o.company_id
                  WHERE m.source_id = :sourceId GROUP BY o.source_id
                  UNION ALL
                  SELECT o.id, TRUE FROM source o JOIN source m ON m.id = :sourceId
                  WHERE m.employer_name IS NOT NULL AND same_title(o.employer_name) = same_title(m.employer_name)
                    AND NOT EXISTS (SELECT 1 FROM company_source x WHERE x.source_id IN (o.id, m.id))) n
                 ON n.source_id = p.source_id
            WHERE p.source_id <> :sourceId AND p.closed_at IS NULL AND v.state <> 'CLOSED'
            ORDER BY p.vacancy_id""", nativeQuery = true)
    List<Object[]> findNeighbourPostings(@Param("sourceId") long sourceId);

    /**
     * Ключ сравнения названия или места — функция БД {@code same_title} (V90, V92): одно правило для склейки при
     * записи, склейки накопленных (V92), отсева profesia.sk (§220) и показателей полноты.
     *
     * @param text название или место; пустая строка — пустой ключ
     * @return текст без регистра, диакритики, скобок, зарплаты, пометок пола и знаков
     */
    @Query(value = "SELECT same_title(:text)", nativeQuery = true)
    String sameTitle(@Param("text") String text);

    /**
     * @param sourceId источник
     * @return внешние id публикаций источника, которые не главные в своей вакансии: название и ссылку вакансии они не
     *         меняют (аудит §143). Главная — первая (по id) публикация не с profesia.sk, нет такой — первая
     *         (§271, аудит §270 замечание 5): после склейки по ссылке из кнопки (§268) более раннее объявление
     *         profesia.sk не перезаписывает ссылку системы найма своей ссылкой на profesia.sk
     */
    @Query("select p.externalId from JobPosting p where p.source.id = ?1 and exists "
            + "(select q.id from JobPosting q where q.vacancy = p.vacancy and q.id <> p.id and ("
            + "q.externalId not like 'profesia:%' and (p.externalId like 'profesia:%' or q.id < p.id) "
            + "or q.externalId like 'profesia:%' and p.externalId like 'profesia:%' and q.id < p.id))")
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
     * Порядок — дольше всех не подтверждённые первыми (§218): проверка пропавших из списка с фильтром по стране
     * ограничена числом за чтение, и без порядка каждое чтение проверяло бы одни и те же первые публикации, а остальные
     * не проверялись бы никогда — не закрывались и не подтверждались (у MSD, PwC, Roche — сотни с начала октября).
     *
     * @param sourceId источник
     * @return внешние id незакрытых публикаций источника, дольше не подтверждённые — первыми
     */
    @Query("select p.externalId from JobPosting p where p.source.id = ?1 and p.closedAt is null"
            + " order by p.lastConfirmedAt, p.id")
    List<String> findOpenExternalIds(Long sourceId);
}
