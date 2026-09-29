package com.roleorienta.worker.career;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сайты компаний к проверке, подключение найденных источников и итог проверки (JDBC).
 */
@Repository
public class CareerScanRepository {

    private static final String COUNTRY = "SK";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public CareerScanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param limit        сколько сайтов
     * @param recheckAfter срок до перепроверки
     * @return сайты действующих компаний, ещё не проверенные или проверенные давнее срока
     */
    public List<Site> nextSites(int limit, Duration recheckAfter) {
        return jdbcTemplate.query("""
                SELECT s.id, s.company_id, s.host FROM company_site s JOIN company c ON c.id = s.company_id
                WHERE c.terminated_on IS NULL
                  AND (s.checked_at IS NULL OR s.checked_at < now() - make_interval(secs => ?))
                ORDER BY s.checked_at NULLS FIRST, s.id LIMIT ?
                """, (row, number) -> new Site(row.getLong("id"), row.getLong("company_id"), row.getString("host")),
                (double) recheckAfter.toSeconds(), limit);
    }

    /**
     * Итог проверки сайта одной транзакцией: найденные источники подключаются (уже подключённый —
     * не дублируется) и связываются с компанией, сайт отмечается проверенным.
     *
     * @param site      сайт
     * @param boards    найденные источники
     * @param result    итог проверки
     * @param careerUrl адрес кадровой страницы; {@code null} — не найдена
     */
    @Transactional
    public void record(Site site, Set<Board> boards, CheckResult result, String careerUrl) {
        for (Board board : boards) {
            jdbcTemplate.update("""
                    INSERT INTO source (provider, board, country) VALUES (?, ?, ?)
                    ON CONFLICT (provider, board) DO NOTHING
                    """, board.provider(), board.board(), COUNTRY);
            jdbcTemplate.update("""
                    INSERT INTO company_source (company_id, source_id)
                    SELECT ?, id FROM source WHERE provider = ? AND board = ?
                    ON CONFLICT DO NOTHING
                    """, site.companyId(), board.provider(), board.board());
        }
        jdbcTemplate.update("UPDATE company_site SET checked_at = now(), check_result = ?, career_url = ? WHERE id = ?",
                result.name(), careerUrl, site.id());
    }

    /**
     * Сайт компании к проверке.
     *
     * @param id        запись {@code company_site}
     * @param companyId компания
     * @param host      хост сайта
     */
    public record Site(long id, long companyId, String host) {
    }

    /**
     * Итог проверки сайта (бизнес-описание §4.2: итог сохраняется с причиной).
     */
    public enum CheckResult {
        /** Источник поддерживаемого формата подключён. */
        SOURCE_FOUND,
        /** Кадровая страница не найдена. */
        NO_CAREER_PAGE,
        /** Кадровая страница есть, формат не поддерживается. */
        FORMAT_UNSUPPORTED,
        /** Сайт не ответил. */
        UNREACHABLE
    }
}
