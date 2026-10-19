package com.roleorienta.api.company;

import com.roleorienta.api.company.CompanyDtos.CareerRequestItem;
import com.roleorienta.api.company.CompanyDtos.Check;
import com.roleorienta.api.company.CompanyDtos.CompanyItem;
import com.roleorienta.api.company.CompanyDtos.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Компании с группой и итогом проверки, адреса кадровых страниц пользователей (§137; бизнес-описание §4.2, §5).
 *
 * <p>Группа: {@code NOT_EMPLOYER} — отметка администратора «не работодатель» (§138); {@code YES} — итог проверки
 * «подключена» (кадровая страница читается или вакансии на портале); {@code MAYBE} — найден сайт; {@code NO} — сайта
 * нет. Только действующие компании; скрытые пользователем ({@code company_hidden}) — не в его поиске и
 * сомнительных.</p>
 */
@Repository
public class CompanyRepository {

    /** Столбцы компании с группой, итогом, сайтом и кадровой страницей. */
    private static final String COMPANY = """
            SELECT c.id, c.name, c.registration_number,
                   CASE WHEN c.not_employer THEN 'NOT_EMPLOYER'
                        WHEN ch.result = 'CONNECTED' THEN 'YES'
                        WHEN EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = c.id AND s.status = 'FOUND')
                            THEN 'MAYBE'
                        ELSE 'NO' END AS company_group,
                   CASE WHEN ch.result = 'CONNECTED' OR c.not_employer THEN NULL ELSE ch.result END AS reason,
                   (SELECT s.host FROM company_site s WHERE s.company_id = c.id AND s.status = 'FOUND'
                    ORDER BY s.id LIMIT 1) AS site,
                   (SELECT s.career_url FROM company_site s WHERE s.company_id = c.id AND s.career_url IS NOT NULL
                    ORDER BY s.id LIMIT 1) AS career_url
            FROM company c LEFT JOIN company_check ch ON ch.company_id = c.id
            WHERE c.terminated_on IS NULL
            """;

    /** Не скрыта пользователем (параметр — пользователь; {@code 0} — никем не скрыта для администратора). */
    private static final String NOT_HIDDEN = """
              AND NOT EXISTS (SELECT 1 FROM company_hidden h WHERE h.company_id = c.id AND h.user_id = ?)
            """;

    /** Найдено столько компаний поиска не больше. */
    private static final int SEARCH_LIMIT = 50;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public CompanyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param userId пользователь — его скрытые компании не ищутся; {@code 0} — все (администратор)
     * @param number регистрационный номер (точно)
     * @param slug   часть названия в виде {@code company.name_slug} (без диакритики, через дефис)
     * @return компании с этим номером или с этой частью названия: с читаемой кадровой страницей первыми, затем
     *         приоритетные, по названию; не больше {@value #SEARCH_LIMIT}
     */
    public List<CompanyItem> search(long userId, String number, String slug) {
        return jdbcTemplate.query(COMPANY + NOT_HIDDEN + """
                  AND (c.registration_number = ? OR c.name_slug LIKE ?)
                ORDER BY coalesce(ch.result = 'CONNECTED', FALSE) AND NOT c.not_employer DESC, c.priority DESC,
                         c.name, c.id
                LIMIT ?""", CompanyRepository::company, userId, number, "%" + slug + "%", SEARCH_LIMIT);
    }

    /**
     * Сомнительные компании (бизнес-описание §5): без читаемой кадровой страницы — с найденным сайтом или, без сайта, с
     * признаком найма (приоритетная — эталон, или 10+ сотрудников по RÚZ); остальные — только поиском (решение
     * владельца 2026-10-19).
     *
     * @param userId пользователь — его скрытые компании не показываются
     * @param after  курсор: id последней компании прежней страницы; {@code 0} — первая
     * @param limit  сколько
     * @return компании по id; без отметки «не работодатель»
     */
    public List<CompanyItem> doubtful(long userId, long after, int limit) {
        return jdbcTemplate.query(COMPANY + NOT_HIDDEN + """
                  AND c.id > ? AND ch.result IS DISTINCT FROM 'CONNECTED' AND NOT c.not_employer
                  AND (c.priority OR c.employees_min >= 10
                       OR EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = c.id AND s.status = 'FOUND'))
                ORDER BY c.id LIMIT ?""", CompanyRepository::company, userId, after, limit);
    }

    /**
     * @param userId пользователь
     * @return компании, скрытые пользователем, последние скрытые первыми
     */
    public List<CompanyItem> hidden(long userId) {
        return jdbcTemplate.query(COMPANY + """
                  AND EXISTS (SELECT 1 FROM company_hidden h WHERE h.company_id = c.id AND h.user_id = ?)
                ORDER BY (SELECT h.created_at FROM company_hidden h WHERE h.company_id = c.id AND h.user_id = ?) DESC
                """, CompanyRepository::company, userId, userId);
    }

    /**
     * @param userId    пользователь
     * @param companyId компания
     */
    public void hide(long userId, long companyId) {
        jdbcTemplate.update("""
                INSERT INTO company_hidden (user_id, company_id) VALUES (?, ?) ON CONFLICT DO NOTHING""",
                userId, companyId);
    }

    /**
     * @param userId    пользователь
     * @param companyId компания
     */
    public void unhide(long userId, long companyId) {
        jdbcTemplate.update("DELETE FROM company_hidden WHERE user_id = ? AND company_id = ?", userId, companyId);
    }

    /**
     * @param companyId   компания
     * @param notEmployer отметка администратора «не работодатель»
     * @return {@code true} — компания есть
     */
    public boolean markNotEmployer(long companyId, boolean notEmployer) {
        return jdbcTemplate.update("UPDATE company SET not_employer = ? WHERE id = ?", notEmployer, companyId) > 0;
    }

    /**
     * @param id компания
     * @return действующая компания
     */
    public Optional<CompanyItem> find(long id) {
        return jdbcTemplate.query(COMPANY + " AND c.id = ?", CompanyRepository::company, id).stream().findFirst();
    }

    /**
     * @param userId пользователь
     * @return сколько адресов пользователь ввёл за последние сутки (по часам БД)
     */
    public int requestsLastDay(long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM company_request WHERE user_id = ? AND created_at >= now() - interval '1 day'
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    /**
     * @param userId    пользователь
     * @param companyId компания
     * @param url       адрес кадровой страницы
     * @return новый запрос «в обработке»
     */
    public CareerRequestItem addRequest(long userId, long companyId, String url) {
        return jdbcTemplate.queryForObject("""
                WITH added AS (
                    INSERT INTO company_request (user_id, company_id, career_url) VALUES (?, ?, ?)
                    RETURNING id, company_id, career_url, state, reason, created_at)
                SELECT a.id, c.name, a.career_url, a.state, a.reason, a.created_at
                FROM added a JOIN company c ON c.id = a.company_id""", CompanyRepository::request,
                userId, companyId, url);
    }

    /**
     * @param userId пользователь
     * @param limit  сколько
     * @return запросы пользователя, новые первыми
     */
    public List<CareerRequestItem> requests(long userId, int limit) {
        return jdbcTemplate.query("""
                SELECT r.id, c.name, r.career_url, r.state, r.reason, r.created_at
                FROM company_request r JOIN company c ON c.id = r.company_id
                WHERE r.user_id = ? ORDER BY r.id DESC LIMIT ?""", CompanyRepository::request, userId, limit);
    }

    private static CompanyItem company(ResultSet row, int number) throws SQLException {
        return new CompanyItem(row.getLong("id"), row.getString("name"), row.getString("registration_number"),
                new Check(row.getString("company_group"), row.getString("reason"), row.getString("site"),
                        row.getString("career_url")));
    }

    private static CareerRequestItem request(ResultSet row, int number) throws SQLException {
        return new CareerRequestItem(row.getLong(1), row.getString(2), row.getString(3), new Status(row.getString(4),
                row.getString(5), row.getObject(6, OffsetDateTime.class).toInstant()));
    }
}
