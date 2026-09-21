package com.roleorienta.worker.digest;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Чтение подписок и связей «публикация → компания» для сопоставления (§39).
 *
 * <p>Читает напрямую SQL: таблицы {@code company_subscription} и {@code company_source}
 * ведёт job-api (их JPA-сущности ссылаются на {@code AppUser}, недоступный воркеру),
 * а воркеру нужны лишь идентификаторы — их отдаёт запрос по общей БД.</p>
 */
@Component
public class SubscriptionLookup {

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Компании, которым принадлежит источник публикации (§4: доска площадки может
     * обслуживать несколько компаний, поэтому список).
     *
     * @param postingId id публикации
     * @return id компаний
     */
    public List<Long> companyIdsForPosting(Long postingId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT cs.company_id FROM company_source cs "
                        + "JOIN job_posting jp ON jp.source_id = cs.source_id WHERE jp.id = ?",
                Long.class, postingId);
    }

    /**
     * Подписчики компании.
     *
     * @param companyId id компании
     * @return id пользователей-подписчиков
     */
    public List<Long> subscriberIdsForCompany(Long companyId) {
        return jdbcTemplate.queryForList(
                "SELECT app_user_id FROM company_subscription WHERE company_id = ?",
                Long.class, companyId);
    }
}
