package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.JobPosting;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Доступ к публикациям для сбора.
 */
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    /**
     * Идемпотентно сохраняет обнаруженную публикацию.
     *
     * <p>При первом обнаружении вставляет строку и ставит {@code first_seen_at}; при
     * повторном (конфликт по уникальному ключу {@code (source_id, external_id)})
     * обновляет только {@code last_seen_at}, ссылку и заголовок, а {@code first_seen_at}
     * сохраняет неизменным. Так «дата первого обнаружения» и «дата последней встречи»
     * остаются разными (§6 техдока, история с первого сбора), а повторная обработка
     * того же задания не создаёт дублей (тот же приём {@code ON CONFLICT}, что в
     * планировщике и идемпотентности потребителя).
     * https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT</p>
     *
     * @param sourceId   идентификатор источника
     * @param externalId идентификатор публикации в терминах источника
     * @param url        ссылка на публикацию
     * @param rawTitle   заголовок в исходном виде
     * @param seenAt     момент наблюдения (идёт и в first_seen_at при вставке, и в last_seen_at)
     * @return 1, если публикация вставлена или обновлена
     */
    @Modifying
    @Query(value = "INSERT INTO job_posting "
            + "(source_id, external_id, url, raw_title, first_seen_at, last_seen_at) "
            + "VALUES (:sourceId, :externalId, :url, :rawTitle, :seenAt, :seenAt) "
            + "ON CONFLICT ON CONSTRAINT uq_job_posting_source_external_id DO UPDATE SET "
            + "url = EXCLUDED.url, raw_title = EXCLUDED.raw_title, "
            + "last_seen_at = EXCLUDED.last_seen_at, updated_at = now()",
            nativeQuery = true)
    int upsert(@Param("sourceId") Long sourceId,
               @Param("externalId") String externalId,
               @Param("url") String url,
               @Param("rawTitle") String rawTitle,
               @Param("seenAt") Instant seenAt);

    /**
     * Находит публикацию по источнику и внешнему ID (пара уникальна). Используется
     * заданием {@code FETCH_POSTING}, чтобы дописать детальные и нормализованные поля
     * к уже существующей строке через её сущность (без длинного native UPDATE).
     *
     * @param sourceId   идентификатор источника
     * @param externalId идентификатор публикации в терминах источника
     * @return публикация, если найдена
     */
    Optional<JobPosting> findBySource_IdAndExternalId(Long sourceId, String externalId);
}
