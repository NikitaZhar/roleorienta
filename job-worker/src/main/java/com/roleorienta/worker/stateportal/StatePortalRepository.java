package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.adapter.stateportal.PortalEmployer;
import com.roleorienta.worker.adapter.stateportal.PortalSite;
import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Работодатели государственного портала ({@code portal_employer}) и подключение их вакансий как
 * источника (технический документ §5.1; бизнес-описание §4.1).
 */
@Repository
public class StatePortalRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public StatePortalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Записывает работодателей страницы списка портала; известный — обновляются название и время, когда
     * он был в списке. Пакетом — один запрос на страницу.
     *
     * @param employers работодатели страницы
     */
    public void saveListed(List<PortalEmployer> employers) {
        if (employers.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO portal_employer (registration_number, name, listed_at) VALUES (?, ?, now())
                ON CONFLICT (registration_number) DO UPDATE SET name = EXCLUDED.name, listed_at = now()
                """, employers, employers.size(), (statement, employer) -> {
                    statement.setString(1, employer.registrationNumber());
                    statement.setString(2, employer.name());
                });
    }

    /**
     * Работодатели портала к проверке: есть в реестре (Словакия, по IČO; страна — активная страна сбора),
     * действуют, не кадровое
     * агентство, у компании ещё нет ни одного источника (своя кадровая страница или уже подключённый
     * портал — один канал на компанию), не проверялись дольше {@code recheckAfter}. Сначала
     * непроверенные.
     *
     * @param limit        не больше
     * @param recheckAfter срок до повторной проверки
     * @return работодатели с компанией
     */
    public List<DueEmployer> employersToCheck(int limit, Duration recheckAfter) {
        return jdbcTemplate.query("""
                SELECT p.registration_number, c.id FROM portal_employer p
                JOIN company c ON c.country = 'SK'
                     AND c.registration_number IN (p.registration_number, ltrim(p.registration_number, '0'))
                JOIN collection_country cc ON cc.country = c.country AND cc.active
                WHERE c.terminated_on IS NULL AND NOT c.agency
                  AND (p.checked_at IS NULL OR p.checked_at < now() - make_interval(secs => ?))
                  AND NOT EXISTS (SELECT 1 FROM company_source cs WHERE cs.company_id = c.id)
                ORDER BY p.checked_at NULLS FIRST, p.registration_number
                LIMIT ?
                """, (row, number) -> new DueEmployer(row.getString(1), row.getLong(2)),
                (double) recheckAfter.toSeconds(), limit);
    }

    /**
     * Итог проверки работодателя одной транзакцией. Вакансии есть — источник портала (доска — IČO)
     * подключается при записанном основании использования ({@code source_permission}), компания
     * связывается с ним как работодатель и получает итог «подключена» ({@code company_check}).
     * Повторный вызов дублей не создаёт.
     *
     * @param employer  работодатель с компанией
     * @param hasOffers есть ли вакансии на портале
     */
    @Transactional
    public void recordCheck(DueEmployer employer, boolean hasOffers) {
        jdbcTemplate.update("UPDATE portal_employer SET checked_at = now(), has_offers = ? WHERE registration_number = ?",
                hasOffers, employer.registrationNumber());
        if (!hasOffers) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO source (provider, board, country) SELECT ?, ?, 'SK'
                WHERE EXISTS (SELECT 1 FROM source_permission WHERE scope = 'PROVIDER' AND provider = ?
                              AND decision = 'ALLOW')
                ON CONFLICT (provider, board) DO NOTHING
                """, StatePortalAdapter.PROVIDER, employer.registrationNumber(), StatePortalAdapter.PROVIDER);
        int linked = jdbcTemplate.update("""
                INSERT INTO company_source (company_id, source_id, role)
                SELECT ?, id, 'EMPLOYER' FROM source WHERE provider = ? AND board = ?
                ON CONFLICT DO NOTHING
                """, employer.companyId(), StatePortalAdapter.PROVIDER, employer.registrationNumber());
        if (linked > 0) {
            jdbcTemplate.update("""
                    INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
                    VALUES (?, 'CONNECTED', now(), now())
                    ON CONFLICT (company_id) DO UPDATE SET result = 'CONNECTED', checked_at = now()
                    """, employer.companyId());
        }
    }

    /**
     * Работодатели портала к поиску сайта (§47): источник портала подключён к их компании, у компании нет
     * ни одного сайта, сайт не искали дольше {@code recheckAfter}. Сначала те, где не искали.
     *
     * @param limit        не больше
     * @param recheckAfter срок до повторного поиска
     * @return работодатели с компанией
     */
    public List<DueEmployer> employersForSite(int limit, Duration recheckAfter) {
        return jdbcTemplate.query("""
                SELECT p.registration_number, c.id FROM portal_employer p
                JOIN source s ON s.provider = ? AND s.board = p.registration_number
                JOIN company_source cs ON cs.source_id = s.id
                JOIN company c ON c.id = cs.company_id
                WHERE c.terminated_on IS NULL
                  AND (p.site_checked_at IS NULL OR p.site_checked_at < now() - make_interval(secs => ?))
                  AND NOT EXISTS (SELECT 1 FROM company_site site WHERE site.company_id = c.id)
                ORDER BY p.site_checked_at NULLS FIRST, p.registration_number
                LIMIT ?
                """, (row, number) -> new DueEmployer(row.getString(1), row.getLong(2)),
                StatePortalAdapter.PROVIDER, (double) recheckAfter.toSeconds(), limit);
    }

    /**
     * Итог поиска сайта одной транзакцией: отметка «искали»; сайт найден — он записывается сайтом компании
     * с источником {@code STATE_PORTAL} (дальше его проверяет поиск кадровой страницы).
     *
     * @param employer работодатель с компанией
     * @param site     сайт; {@code host == null} — не найден
     */
    @Transactional
    public void recordSite(DueEmployer employer, PortalSite site) {
        jdbcTemplate.update("UPDATE portal_employer SET site_checked_at = now() WHERE registration_number = ?",
                employer.registrationNumber());
        if (site.host() != null) {
            jdbcTemplate.update("""
                    INSERT INTO company_site (company_id, host, evidence_url, source) VALUES (?, ?, ?, 'STATE_PORTAL')
                    ON CONFLICT (company_id, host) DO NOTHING
                    """, employer.companyId(), site.host(), site.evidenceUrl());
        }
    }

    /**
     * Работодатель портала, найденный в реестре.
     *
     * @param registrationNumber IČO, как на портале
     * @param companyId          компания реестра
     */
    public record DueEmployer(String registrationNumber, long companyId) {
    }
}
