package com.roleorienta.worker.career;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сайты компаний к проверке, подключение найденных источников и итог проверки (JDBC).
 */
@Repository
public class CareerScanRepository {

    /**
     * Итог компании — лучший из итогов её проверенных сайтов (бизнес-описание §4.2); первая дата итога
     * сохраняется.
     */
    private static final String COMPANY_RESULT = """
            INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
            SELECT company_id,
                   CASE max(CASE check_result WHEN 'SOURCE_FOUND' THEN 5 WHEN 'USE_FORBIDDEN' THEN 4
                                              WHEN 'FORMAT_UNSUPPORTED' THEN 3 WHEN 'NO_CAREER_PAGE' THEN 2 ELSE 1 END)
                       WHEN 5 THEN 'CONNECTED' WHEN 4 THEN 'USE_FORBIDDEN' WHEN 3 THEN 'FORMAT_UNSUPPORTED'
                       WHEN 2 THEN 'PAGE_NOT_FOUND' ELSE 'SOURCE_UNAVAILABLE' END,
                   now(), now()
            FROM company_site WHERE company_id = ? AND check_result IS NOT NULL GROUP BY company_id
            ON CONFLICT (company_id) DO UPDATE SET result = EXCLUDED.result, checked_at = EXCLUDED.checked_at
            """;

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
     * @return сайты действующих компаний активных стран сбора, ещё не проверенные или проверенные давнее
     *         срока
     */
    public List<Site> nextSites(int limit, Duration recheckAfter) {
        return jdbcTemplate.query("""
                SELECT s.id, s.company_id, s.host FROM company_site s JOIN company c ON c.id = s.company_id
                JOIN collection_country cc ON cc.country = c.country AND cc.active
                WHERE c.terminated_on IS NULL
                  AND (s.checked_at IS NULL OR s.checked_at < now() - make_interval(secs => ?))
                ORDER BY s.checked_at NULLS FIRST, s.id LIMIT ?
                """, (row, number) -> new Site(row.getLong("id"), row.getLong("company_id"), row.getString("host")),
                (double) recheckAfter.toSeconds(), limit);
    }

    /**
     * Итог проверки сайта одной транзакцией: найденные источники подключаются (уже подключённый —
     * не дублируется) и связываются с компанией, сайт отмечается проверенным, итог компании
     * ({@code company_check}) пересчитывается.
     *
     * @param site      сайт
     * @param boards    найденные источники
     * @param result    итог проверки
     * @param careerUrl адрес кадровой страницы; {@code null} — не найдена
     * @param role      роль компании у подключаемых источников
     */
    @Transactional
    public void record(Site site, Set<Board> boards, CheckResult result, String careerUrl, Role role) {
        for (Board board : boards) {
            jdbcTemplate.update("""
                    INSERT INTO source (provider, board, country) SELECT ?, ?, country FROM company WHERE id = ?
                    ON CONFLICT (provider, board) DO NOTHING
                    """, board.provider(), board.board(), site.companyId());
            jdbcTemplate.update("""
                    INSERT INTO company_source (company_id, source_id, role)
                    SELECT ?, id, ? FROM source WHERE provider = ? AND board = ?
                    ON CONFLICT DO NOTHING
                    """, site.companyId(), role.name(), board.provider(), board.board());
        }
        jdbcTemplate.update("UPDATE company_site SET checked_at = now(), check_result = ?, career_url = ? WHERE id = ?",
                result.name(), careerUrl, site.id());
        jdbcTemplate.update(COMPANY_RESULT, site.companyId());
    }

    /**
     * @return провайдеры, использование которых разрешено ({@code source_permission}, бизнес-описание §10)
     */
    public Set<String> permittedProviders() {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT provider FROM source_permission WHERE scope = 'PROVIDER' AND decision = 'ALLOW'
                """, String.class));
    }

    /**
     * @param companyId компания
     * @return роль компании у её источников: работодатель; кадровое агентство — только с разрешением на
     *         него; пусто — агентство без разрешения, подключать нельзя
     */
    public Optional<Role> permittedRole(long companyId) {
        return jdbcTemplate.query("""
                SELECT c.agency, EXISTS (SELECT 1 FROM source_permission p WHERE p.scope = 'AGENCY'
                                         AND p.company_id = c.id AND p.decision = 'ALLOW') AS allowed
                FROM company c WHERE c.id = ?
                """, (row, number) -> !row.getBoolean("agency") ? Optional.of(Role.EMPLOYER)
                : row.getBoolean("allowed") ? Optional.of(Role.AGENCY) : Optional.<Role>empty(), companyId)
                .stream().findFirst().orElse(Optional.empty());
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
        UNREACHABLE,
        /** Использование не разрешено: нет основания для провайдера или агентства, запрет robots.txt. */
        USE_FORBIDDEN
    }

    /**
     * Роль компании у источника (технический документ §4, {@code CompanyCareerPage}).
     */
    public enum Role {
        /** Работодатель. */
        EMPLOYER,
        /** Кадровое агентство — размещающая сторона. */
        AGENCY
    }
}
