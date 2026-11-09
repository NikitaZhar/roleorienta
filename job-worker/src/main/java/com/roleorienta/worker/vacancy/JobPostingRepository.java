package com.roleorienta.worker.vacancy;

import com.roleorienta.worker.lock.PostgresLeaderLock;
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
     * Разделяемая блокировка записи вакансий ({@link PostgresLeaderLock#VACANCY_WRITE}, §271): запись чтения
     * источника не идёт одновременно со склейкой вакансий. Первым запросом транзакции записи.
     *
     * @return 1
     */
    @Query(value = PostgresLeaderLock.VACANCY_WRITE_SHARED, nativeQuery = true)
    int lockVacancyWriteShared();

    /**
     * Вакансия по сильному признаку (docs/vacancy-identity-plan.md §4, признаки 1–3; стенограмма §279) — у всех открытых
     * публикаций незакрытых вакансий, без отбора по работодателю и с источником новой публикации: (1) тот же номер
     * объявления profesia.sk (портал, добор, кадровая страница на {@code *.profesia.sk}); (2) тот же адрес без
     * сессионных параметров ({@code clean_address}, V102); (3) тот же провайдер, хост и внешний id, у Workday — и без
     * суффикса копии {@code -N} при том же ключе названия (одна вакансия на двух сайтах Workday одного хоста, HPE).
     * Ключи у записанных публикаций — вычисляемые колонки V102; у новой их считают те же функции в запросе. Хост — без
     * схемы и {@code www.}, до «/», «?» ({@code chr(63)}: знак вопроса в тексте запроса читался бы как параметр).
     *
     * @param provider   провайдер источника новой публикации
     * @param externalId её внешний id
     * @param url        её ссылка
     * @param title      её название
     * @return вакансия первого сработавшего признака, при нескольких — с наименьшим id; пусто — нет
     */
    @Query(value = """
            SELECT m.vacancy_id FROM (
                SELECT 1 AS signal, p.vacancy_id FROM job_posting p
                WHERE p.closed_at IS NULL AND p.profesia_num = profesia_number(:externalId, :url)
                UNION ALL
                SELECT 2, p.vacancy_id FROM job_posting p
                WHERE p.closed_at IS NULL AND p.address_key = clean_address(:url)
                UNION ALL
                SELECT 3, p.vacancy_id FROM job_posting p JOIN source s ON s.id = p.source_id AND s.provider = :provider
                WHERE p.closed_at IS NULL
                  AND split_part(split_part(regexp_replace(lower(p.url), '^[a-z]+://(www[.])*', ''), '/', 1), chr(63), 1)
                      = split_part(split_part(regexp_replace(lower(:url), '^[a-z]+://(www[.])*', ''), '/', 1), chr(63), 1)
                  AND (p.external_id = :externalId
                       OR :provider = 'workday'
                          AND regexp_replace(p.external_id, '-[0-9]+$', '') = regexp_replace(:externalId, '-[0-9]+$', '')
                          AND p.title_key = vacancy_title_key(:title))) m
            JOIN vacancy v ON v.id = m.vacancy_id AND v.state <> 'CLOSED'
            ORDER BY m.signal, m.vacancy_id
            LIMIT 1""", nativeQuery = true)
    Optional<Long> findStrongMatch(@Param("provider") String provider, @Param("externalId") String externalId,
            @Param("url") String url, @Param("title") String title);

    /**
     * Вакансия по названию и месту (docs/vacancy-identity-plan.md §4, признак 5; стенограмма §279) — у работодателя
     * источника: компании источника с ролью {@code EMPLOYER}, их источники с той же ролью, в том числе сам источник, и
     * объявления добора, чья компания ({@code profesia_offer.company_id}) — эта. Источник без такой компании — нет
     * (название работодателя без компании не различает работодателей). Кандидаты — открытые публикации незакрытых
     * вакансий с тем же ключом названия ({@code vacancy_title_key}); места вакансии — места всех её таких публикаций.
     * <ul>
     *   <li>5а: у обеих есть города — одна вакансия, если города пересекаются, иначе разные; городов нет у обеих, но
     *       есть края — так же по краям;</li>
     *   <li>5б: иначе (только общее место у одной или обеих, или город против края) — одна, только если у работодателя
     *       ровно одна вакансия с этим названием (с новой публикацией их две).</li>
     * </ul>
     *
     * @param sourceId источник новой публикации
     * @param title    её название
     * @param location её место; {@code null} — нет
     * @return вакансия: совпавшая по 5а с наименьшим id, иначе единственная по 5б; пусто — новая вакансия
     */
    @Query(value = """
            WITH k AS (SELECT vacancy_title_key(:title) AS title_key, vacancy_places(:location) AS places),
            employer AS (SELECT company_id FROM company_source WHERE source_id = :sourceId AND role = 'EMPLOYER'),
            candidate AS (
                SELECT p.vacancy_id, p.place_keys
                FROM job_posting p JOIN vacancy v ON v.id = p.vacancy_id AND v.state <> 'CLOSED' CROSS JOIN k
                WHERE p.closed_at IS NULL AND k.title_key <> '' AND p.title_key = k.title_key
                  AND (p.source_id IN (SELECT cs.source_id FROM company_source cs
                                       JOIN employer e ON e.company_id = cs.company_id WHERE cs.role = 'EMPLOYER')
                       OR p.external_id IN (SELECT 'profesia:' || o.num FROM profesia_offer o
                                            JOIN employer e ON e.company_id = o.company_id))),
            grouped AS (
                SELECT c.vacancy_id, array_remove(array_agg(DISTINCT place), NULL) AS places
                FROM candidate c LEFT JOIN LATERAL unnest(c.place_keys) place ON TRUE
                GROUP BY c.vacancy_id),
            judged AS (
                SELECT g.vacancy_id,
                       CASE WHEN nc.cities <> '{}' AND gc.cities <> '{}'
                                 THEN CASE WHEN nc.cities && gc.cities THEN 'same' ELSE 'different' END
                            WHEN nc.cities = '{}' AND gc.cities = '{}' AND nc.regions <> '{}' AND gc.regions <> '{}'
                                 THEN CASE WHEN nc.regions && gc.regions THEN 'same' ELSE 'different' END
                            ELSE 'loose' END AS relation
                FROM grouped g CROSS JOIN k
                CROSS JOIN LATERAL (SELECT ARRAY(SELECT x FROM unnest(k.places) x WHERE x LIKE 'c:%') AS cities,
                                           ARRAY(SELECT x FROM unnest(k.places) x WHERE x LIKE 'r:%') AS regions) nc
                CROSS JOIN LATERAL (SELECT ARRAY(SELECT x FROM unnest(g.places) x WHERE x LIKE 'c:%') AS cities,
                                           ARRAY(SELECT x FROM unnest(g.places) x WHERE x LIKE 'r:%') AS regions) gc)
            SELECT vacancy_id FROM judged
            WHERE relation = 'same' OR relation = 'loose' AND (SELECT count(*) FROM grouped) = 1
            ORDER BY relation = 'same' DESC, vacancy_id
            LIMIT 1""", nativeQuery = true)
    Optional<Long> findTitleAndPlaceMatch(@Param("sourceId") long sourceId, @Param("title") String title,
            @Param("location") String location);

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
