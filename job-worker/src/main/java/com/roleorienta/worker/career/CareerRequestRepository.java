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
     * @param keyPrefix начало ключа задания проверки (за ним — номер запроса)
     * @param limit     не больше
     * @return ожидающие проверки, для которых задания ещё нет, старые первыми: поставленные не ставятся снова и не
     *         занимают место новых (аудит §143)
     */
    public List<Long> pendingWithoutTask(String keyPrefix, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT r.id FROM company_request r
                WHERE r.state = 'PENDING' AND NOT EXISTS (SELECT 1 FROM task t WHERE t.task_key = CAST(? AS text) || r.id)
                ORDER BY r.id LIMIT ?""", Long.class, keyPrefix, limit);
    }

    /**
     * Ожидающие запросы, задание проверки которых не выполнилось после всех попыток ({@code FAILED}), — «не
     * подключена», причина «страница не открылась».
     *
     * @param keyPrefix начало ключа задания проверки
     * @return сколько запросов закрыто
     */
    public int failWithFailedTask(String keyPrefix) {
        return jdbcTemplate.update("""
                UPDATE company_request r SET state = 'NOT_CONNECTED', reason = ?, checked_at = now()
                WHERE r.state = 'PENDING'
                  AND EXISTS (SELECT 1 FROM task t WHERE t.task_key = CAST(? AS text) || r.id AND t.state = 'FAILED')""",
                Reason.UNREACHABLE.name(), keyPrefix);
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
