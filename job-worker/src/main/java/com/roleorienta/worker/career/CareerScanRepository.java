package com.roleorienta.worker.career;

import java.time.Duration;
import java.util.Comparator;
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
     * Основной сайт компании (алгоритм поиска сайта версии 3, технический документ §5.1; стенограмма §52):
     * найденный (кандидат — нет) сайт самого раннего шага — Wikidata, портал, адрес по названию с IČO, адрес по
     * названию с брендом или сайтом группы, Common Crawl; при равенстве — записанный раньше. Подзапрос по
     * компании {@code s.company_id} возвращает id основного сайта.
     */
    private static final String MAIN_SITE = """
            (SELECT m.id FROM company_site m WHERE m.company_id = s.company_id AND m.status = 'FOUND'
             ORDER BY CASE WHEN m.source = 'WIKIDATA' THEN 1 WHEN m.source = 'STATE_PORTAL' THEN 2
                           WHEN m.source = 'NAME' AND m.proof = 'REGISTRATION_NUMBER' THEN 3
                           WHEN m.source = 'NAME' THEN 4 ELSE 5 END, m.id
             LIMIT 1)""";

    /**
     * Проверяемые сайты компании (§80): основной сайт и прочие найденные сайты, кроме сайтов из Common Crawl — у
     * кадровой страницы на втором сайте ({@code scania.com} рядом со {@code scania.sk}) больше шансов, а чужой сайт с
     * IČO компании (интернет-магазин с IČO перевозчика) приходит почти только из Common Crawl (§52, §75).
     */
    private static final String CHECKED_SITE = """
            s.status = 'FOUND' AND (s.source <> 'COMMON_CRAWL' OR s.id = %s)""".formatted(MAIN_SITE);

    /**
     * Кандидат компании без найденного сайта (§81: сайт группы, ответ 403) — проверяется; найденные доски
     * подключаются с ролью «кадровый сайт группы» ({@link Role#GROUP}): принадлежность не подтверждена, поэтому итог
     * компании не меняется, а вакансии доски — только страны сбора (отбор адаптера по стране).
     */
    private static final String CANDIDATE_SITE = """
            s.status = 'CANDIDATE' AND NOT EXISTS (SELECT 1 FROM company_site f
                                                   WHERE f.company_id = s.company_id AND f.status = 'FOUND')""";

    /**
     * Итог компании — лучший итог её проверяемых сайтов ({@link #CHECKED_SITE}; бизнес-описание §4.2); компания с
     * любым подключённым источником (в том числе государственного портала, §45) — «подключена». Первая дата итога
     * сохраняется.
     */
    private static final String COMPANY_RESULT = """
            INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
            SELECT company_id,
                   CASE WHEN EXISTS (SELECT 1 FROM company_source cs WHERE cs.company_id = s.company_id
                                     AND cs.role <> 'GROUP')
                            THEN 'CONNECTED'
                       ELSE CASE max(CASE check_result WHEN 'SOURCE_FOUND' THEN 5 WHEN 'USE_FORBIDDEN' THEN 4
                                              WHEN 'FORMAT_UNSUPPORTED' THEN 3 WHEN 'NO_CAREER_PAGE' THEN 2 ELSE 1 END)
                       WHEN 5 THEN 'CONNECTED' WHEN 4 THEN 'USE_FORBIDDEN' WHEN 3 THEN 'FORMAT_UNSUPPORTED'
                       WHEN 2 THEN 'PAGE_NOT_FOUND' ELSE 'SOURCE_UNAVAILABLE' END END,
                   now(), now()
            FROM company_site s WHERE s.company_id = ? AND %s AND s.check_result IS NOT NULL
            GROUP BY s.company_id
            ON CONFLICT (company_id) DO UPDATE SET result = EXCLUDED.result, checked_at = EXCLUDED.checked_at
            """.formatted(CHECKED_SITE);

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
     * @return проверяемые сайты ({@link #CHECKED_SITE}) и кандидаты компаний без найденного сайта
     *         ({@link #CANDIDATE_SITE}) действующих компаний активных стран
     *         сбора, ещё не проверенные или проверенные давнее срока, — не больше одного сайта компании за задание:
     *         сайты задания проверяются одновременно, а итог компании пересчитывается по всем её сайтам — два сайта
     *         одной компании в одном задании записали бы итог, не видя друг друга (§80); сайты приоритетных
     *         компаний — первыми (§71)
     */
    public List<Site> nextSites(int limit, Duration recheckAfter) {
        return jdbcTemplate.query("""
                SELECT id, company_id, host, start_url, status FROM (
                    SELECT s.id, s.company_id, s.host, s.start_url, s.status, c.priority, s.checked_at,
                           row_number() OVER (PARTITION BY s.company_id ORDER BY s.checked_at NULLS FIRST, s.id)
                               AS company_order
                    FROM company_site s JOIN company c ON c.id = s.company_id
                    JOIN collection_country cc ON cc.country = c.country AND cc.active
                    WHERE c.terminated_on IS NULL AND (%s OR %s)
                      AND (s.checked_at IS NULL OR s.checked_at < now() - make_interval(secs => ?))) due
                WHERE company_order = 1
                ORDER BY priority DESC, checked_at NULLS FIRST, id LIMIT ?
                """.formatted(CHECKED_SITE, CANDIDATE_SITE), (row, number) -> new Site(row.getLong("id"),
                        row.getLong("company_id"), row.getString("host"), row.getString("start_url"),
                        "CANDIDATE".equals(row.getString("status"))),
                (double) recheckAfter.toSeconds(), limit);
    }

    /**
     * Итог проверки сайта одной транзакцией: найденные источники подключаются (уже подключённый —
     * не дублируется) и связываются с компанией, сайт отмечается проверенным, итог компании
     * ({@code company_check}) пересчитывается. У кандидата (§81) доски подключаются с ролью {@link Role#GROUP}
     * (кадровое агентство — не подключаются), итог компании не меняется.
     *
     * @param site      сайт
     * @param boards    найденные источники
     * @param result    итог проверки
     * @param careerUrl адрес кадровой страницы; {@code null} — не найдена
     * @param role      роль компании у подключаемых источников
     */
    @Transactional
    public void record(Site site, Set<Board> boards, CheckResult result, String careerUrl, Role role) {
        jdbcTemplate.update("UPDATE company_site SET checked_at = now(), check_result = ?, career_url = ? WHERE id = ?",
                result.name(), careerUrl, site.id());
        if (site.candidate() && role != Role.EMPLOYER) {
            return;
        }
        Role linkRole = site.candidate() ? Role.GROUP : role;
        // Доски — в одном порядке во всех потоках: встречные вставки двух транзакций не ждут друг друга (аудит §78).
        for (Board board : boards.stream().sorted(Comparator.comparing(Board::provider).thenComparing(Board::board))
                .toList()) {
            jdbcTemplate.update("""
                    INSERT INTO source (provider, board, country) SELECT ?, ?, country FROM company WHERE id = ?
                    ON CONFLICT (provider, board) DO NOTHING
                    """, board.provider(), board.board(), site.companyId());
            jdbcTemplate.update("""
                    INSERT INTO company_source (company_id, source_id, role)
                    SELECT ?, id, ? FROM source WHERE provider = ? AND board = ?
                    ON CONFLICT DO NOTHING
                    """, site.companyId(), linkRole.name(), board.provider(), board.board());
        }
        if (!site.candidate()) {
            jdbcTemplate.update(COMPANY_RESULT, site.companyId());
        }
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
     * @param startUrl  адрес стартовой страницы (с портала, с путём); {@code null} — главная хоста
     * @param candidate кандидат (§81): доски — с ролью {@link Role#GROUP}, итог компании не пересчитывается
     */
    public record Site(long id, long companyId, String host, String startUrl, boolean candidate) {
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
        AGENCY,
        /**
         * Кадровый сайт группы (§81): доска на сайте-кандидате компании без найденного сайта; итог компании и канал
         * портала не меняет.
         */
        GROUP
    }
}
