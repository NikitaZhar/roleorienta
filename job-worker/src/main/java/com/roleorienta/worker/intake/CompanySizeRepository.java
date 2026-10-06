package com.roleorienta.worker.intake;

import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Данные шага «число сотрудников из RÚZ»: основание использования, компании к запросу, запись категории.
 */
@Repository
public class CompanySizeRepository {

    /** Провайдер в реестре оснований ({@code source_permission}). */
    static final String PROVIDER = "registeruz";
    private static final String COUNTRY = "SK";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public CompanySizeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return есть ли записанное разрешение на использование RÚZ (технический документ §10)
     */
    public boolean permitted() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM source_permission
                               WHERE scope = 'PROVIDER' AND provider = ? AND decision = 'ALLOW')
                """, Boolean.class, PROVIDER));
    }

    /**
     * Компании к запросу: действующие компании Словакии — активной страны сбора, — которых не спрашивали или
     * спрашивали давнее срока. Сначала не спрошенные.
     *
     * @param limit        не больше
     * @param recheckAfter срок до повторного запроса
     * @return компании
     */
    public List<DueCompany> dueCompanies(int limit, Duration recheckAfter) {
        return jdbcTemplate.query("""
                SELECT c.id, c.registration_number FROM company c
                JOIN collection_country cc ON cc.country = c.country AND cc.active
                WHERE c.country = ? AND c.terminated_on IS NULL
                  AND (c.employees_checked_at IS NULL
                       OR c.employees_checked_at < now() - make_interval(secs => ?))
                ORDER BY c.employees_checked_at NULLS FIRST, c.id
                LIMIT ?
                """, (row, number) -> new DueCompany(row.getLong(1), row.getString(2)),
                COUNTRY, (double) recheckAfter.toSeconds(), limit);
    }

    /**
     * @param companyId    компания
     * @param employeesMin нижняя граница числа сотрудников; {@code null} — неизвестно
     */
    public void record(long companyId, Integer employeesMin) {
        jdbcTemplate.update("UPDATE company SET employees_min = ?, employees_checked_at = now() WHERE id = ?",
                employeesMin, companyId);
    }

    /**
     * Компания к запросу.
     *
     * @param id                 компания
     * @param registrationNumber IČO
     */
    public record DueCompany(long id, String registrationNumber) {
    }
}
