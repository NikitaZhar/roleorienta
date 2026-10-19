package com.roleorienta.worker.career;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Адреса кадровых страниц, введённые пользователями ({@code company_request}, §137; бизнес-описание §5): ожидающие
 * проверки и итог проверки.
 */
@Repository
public class CareerRequestRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public CareerRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param limit не больше
     * @return ожидающие проверки, старые первыми
     */
    public List<Long> pending(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM company_request WHERE state = 'PENDING' ORDER BY id LIMIT ?", Long.class, limit);
    }

    /**
     * @param id запрос
     * @return ожидающий проверки запрос; пусто — нет или уже проверен
     */
    public Optional<Request> pendingRequest(long id) {
        return jdbcTemplate.query("""
                SELECT company_id, career_url FROM company_request WHERE id = ? AND state = 'PENDING'""",
                (row, number) -> new Request(id, row.getLong("company_id"), row.getString("career_url")), id)
                .stream().findFirst();
    }

    /**
     * @param id     запрос
     * @param reason причина неудачи; {@code null} — подключена
     */
    public void finish(long id, Reason reason) {
        jdbcTemplate.update("UPDATE company_request SET state = ?, reason = ?, checked_at = now() WHERE id = ?",
                reason == null ? "CONNECTED" : "NOT_CONNECTED", reason == null ? null : reason.name(), id);
    }

    /**
     * Запрос к проверке.
     *
     * @param id        запрос
     * @param companyId компания
     * @param careerUrl введённый адрес
     */
    public record Request(long id, long companyId, String careerUrl) {
    }

    /**
     * Причина, по которой адрес не подключён.
     */
    public enum Reason {
        /** Страница не открылась. */
        UNREACHABLE,
        /** Запрет robots.txt или нет основания использования. */
        USE_FORBIDDEN,
        /** Страница не кадровая. */
        NO_CAREER_PAGE,
        /** Кадровая страница, но читаемого на ней нет. */
        FORMAT_UNSUPPORTED
    }
}
