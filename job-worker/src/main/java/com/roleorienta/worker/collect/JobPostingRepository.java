package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.JobPosting;
import java.time.Instant;
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
     * Записывает детальные поля публикации, добранные заданием {@code FETCH_POSTING}.
     * Публикация уже существует (её создал {@code DISCOVER_PAGE}), поэтому это UPDATE
     * по уникальной паре {@code (source_id, external_id)}. Значения сырые; {@code null}
     * означает «поле отсутствует в источнике».
     *
     * @param sourceId        идентификатор источника
     * @param externalId      идентификатор публикации в терминах источника
     * @param rawLocation     сырая локация или {@code null}
     * @param rawCompensation сырая строка зарплаты/компенсации или {@code null}
     * @param fetchedAt       момент дозапроса детали
     * @return число обновлённых строк (1, если публикация найдена)
     */
    @Modifying
    @Query(value = "UPDATE job_posting SET "
            + "raw_location = :rawLocation, raw_compensation = :rawCompensation, "
            + "detail_fetched_at = :fetchedAt, updated_at = now() "
            + "WHERE source_id = :sourceId AND external_id = :externalId",
            nativeQuery = true)
    int updateDetails(@Param("sourceId") Long sourceId,
                      @Param("externalId") String externalId,
                      @Param("rawLocation") String rawLocation,
                      @Param("rawCompensation") String rawCompensation,
                      @Param("fetchedAt") Instant fetchedAt);
}
