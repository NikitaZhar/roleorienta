package com.roleorienta.worker.intake;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Страны общего сбора и очередь их партий (таблица {@code collection_country}; технический документ
 * §4 {@code CollectionCountry}, §5.1; бизнес-описание §4.1).
 */
@Repository
public class CollectionRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public CollectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Страны сбора — объединение стран активных условий поиска: новая страна добавляется, страна, которую
     * сняли все пользователи, выходит из чередования (её подключённые источники читаются дальше). У
     * активных стран снова есть что читать — очередь начинается заново.
     */
    @Transactional
    public void refreshCountries() {
        jdbcTemplate.update("""
                INSERT INTO collection_country (country)
                SELECT DISTINCT unnest(countries) FROM search_condition WHERE active
                ON CONFLICT (country) DO NOTHING
                """);
        jdbcTemplate.update("""
                UPDATE collection_country
                SET active = country IN (SELECT unnest(countries) FROM search_condition WHERE active),
                    has_more = TRUE
                """);
    }

    /**
     * @return активные страны, которым есть что читать, в очереди: сначала та, чья последняя партия
     *         старше всех (без партий — первыми)
     */
    public List<Turn> queue() {
        return jdbcTemplate.query("""
                SELECT country, batches FROM collection_country WHERE active AND has_more
                ORDER BY last_batch_at NULLS FIRST, country
                """, (row, number) -> new Turn(row.getString("country"), row.getLong("batches")));
    }

    /**
     * Партия страны прочитана.
     *
     * @param country страна
     * @param hasMore записи ещё есть
     */
    public void recordBatch(String country, boolean hasMore) {
        jdbcTemplate.update("""
                UPDATE collection_country SET last_batch_at = now(), batches = batches + 1, has_more = ?,
                    registry_read_at = CASE WHEN ? THEN registry_read_at ELSE coalesce(registry_read_at, now()) END
                WHERE country = ?
                """, hasMore, hasMore, country);
    }

    /**
     * Первичный обход страны завершён (бизнес-описание §4.1, технический документ §5.1): реестр прочитан
     * до конца и каждая действующая компания страны получила итог проверки ({@code company_check}).
     * Дата завершения ставится один раз.
     */
    public void markFirstPassDone() {
        jdbcTemplate.update("""
                UPDATE collection_country cc SET first_pass_done_at = now()
                WHERE first_pass_done_at IS NULL AND registry_read_at IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM company c WHERE c.country = cc.country AND c.terminated_on IS NULL
                                    AND NOT EXISTS (SELECT 1 FROM company_check k WHERE k.company_id = c.id))
                """);
    }

    /**
     * Очередь страны.
     *
     * @param country страна
     * @param batches прочитано партий — часть ключа следующего задания
     */
    public record Turn(String country, long batches) {
    }
}
