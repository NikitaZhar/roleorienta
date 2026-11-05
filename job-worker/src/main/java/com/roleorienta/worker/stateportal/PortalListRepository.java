package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.adapter.stateportal.PortalOffer;
import com.roleorienta.worker.adapter.stateportal.ProfesiaPosition;
import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Общий список вакансий портала в БД (стенограмма §105): полные обходы ({@code portal_cycle}), вакансии
 * ({@code portal_offer}) и IČO по названию работодателя ({@code portal_employer_name}).
 */
@Repository
public class PortalListRepository {

    /**
     * Начало доски источника портала у работодателя без IČO (бизнес-описание §4.1, стенограмма §258): {@code name:} и
     * название работодателя из объявления. Доска остальных источников портала — IČO; у этих объявлений его нет.
     */
    public static final String NAME_BOARD = "name:";

    /**
     * Доска по названию работодателя ({@link #NAME_BOARD}) в пределах длины {@code source.board} (200); {@code %s} —
     * выражение названия.
     */
    private static final String NAME_BOARD_OF = "left('" + NAME_BOARD + "' || %s, 200)";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public PortalListRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param sourceId источник портала
     * @return внешние id его публикаций, у которых уже есть код профессии (§203) или страница прочитана, а кода на ней
     *         нет (аудит §211: такая страница больше не запрашивается)
     */
    public Set<String> externalIdsWithOccupation(long sourceId) {
        return new HashSet<>(jdbcTemplate.queryForList("SELECT external_id FROM job_posting WHERE source_id = ? "
                + "AND (occupation_code IS NOT NULL OR occupation_checked)", String.class, sourceId));
    }

    /**
     * @param sourceId источник портала
     * @return внешние id его публикаций, у которых страница объявления уже прочитана ради ссылки на работодателя (§209)
     */
    public Set<String> externalIdsWithEmployerLinkChecked(long sourceId) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT external_id FROM job_posting WHERE source_id = ? AND employer_link_checked",
                String.class, sourceId));
    }

    /**
     * @param sourceId источник портала
     * @return внешние id его объявлений profesia.sk, у которых позиции со страницы уже прочитаны (§267)
     */
    public Set<String> externalIdsWithProfesiaPositionsChecked(long sourceId) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT external_id FROM job_posting WHERE source_id = ? AND profesia_positions_checked",
                String.class, sourceId));
    }

    /**
     * Открытые публикации объявлений profesia.sk любого источника (общий список портала, добор с profesia.sk), у которых
     * позиции со страницы ещё не прочитаны (§267) и объявление не прочитано ради них добором; сначала работодатели с
     * большим числом таких объявлений.
     *
     * @param limit сколько
     * @return источник, внешний id, адрес страницы объявления
     */
    public List<UncheckedAd> uncheckedProfesiaAds(int limit) {
        return jdbcTemplate.query("""
                WITH ad AS (SELECT p.source_id, p.external_id, p.url,
                                   count(*) OVER (PARTITION BY p.source_id) AS per_source
                            FROM job_posting p
                            WHERE p.closed_at IS NULL AND p.external_id LIKE 'profesia:%'
                              AND NOT p.profesia_positions_checked
                              AND NOT EXISTS (SELECT 1 FROM profesia_offer o WHERE o.positions_read
                                              AND 'profesia:' || o.num = p.external_id))
                SELECT source_id, external_id, url FROM ad ORDER BY per_source DESC, source_id, external_id LIMIT ?
                """, (row, number) -> new UncheckedAd(row.getLong(1), row.getString(2), row.getString(3)), limit);
    }

    /**
     * Публикация объявления profesia.sk без прочитанных позиций.
     *
     * @param sourceId   источник
     * @param externalId внешний id {@code profesia:<номер>}
     * @param url        адрес страницы объявления
     */
    public record UncheckedAd(long sourceId, String externalId, String url) {
    }

    /**
     * Позиции profesia.sk со страниц объявлений (§267): записываются по номеру объявления, публикация отмечается
     * прочитанной и без позиций — повторно ради них не запрашивается; одной транзакцией.
     *
     * @param sourceId  источник портала
     * @param positions позиции по внешнему id объявления, чья страница прочитана
     */
    @Transactional
    public void recordProfesiaPositions(long sourceId, Map<String, List<ProfesiaPosition>> positions) {
        positions.forEach((externalId, list) -> {
            long num = ProfesiaPositionRecords.number(externalId);
            if (num >= 0) {
                ProfesiaPositionRecords.save(jdbcTemplate, num, list);
            }
            jdbcTemplate.update("UPDATE job_posting SET profesia_positions_checked = TRUE WHERE source_id = ? "
                    + "AND external_id = ?", sourceId, externalId);
        });
    }

    /**
     * Ссылки на вакансию у работодателя со страниц объявлений profesia.sk (§209): публикация отмечается прочитанной и
     * со ссылкой, и без неё ({@code null}) — повторно не запрашивается; публикации нет — ничего.
     *
     * @param sourceId источник портала
     * @param links    ссылка по внешнему id; {@code null} — у объявления её нет
     */
    public void recordEmployerLinks(long sourceId, Map<String, String> links) {
        links.forEach((externalId, link) -> jdbcTemplate.update(
                "UPDATE job_posting SET employer_url = ?, employer_link_checked = TRUE WHERE source_id = ? "
                        + "AND external_id = ? AND NOT employer_link_checked", link, sourceId, externalId));
    }

    /**
     * Коды профессий со страниц объявлений (§203) — публикациям источника; страница прочитана — отмечается, и без кода
     * ({@code null}) больше не запрашивается; код получен — распознанная по названию группа снимается (§215, аудит
     * §222: код портала точнее); публикации нет — ничего.
     *
     * @param sourceId    источник портала
     * @param occupations группа ISCO-08 (4 цифры) по внешнему id; {@code null} — кода на странице нет
     */
    public void recordOccupations(long sourceId, Map<String, String> occupations) {
        occupations.forEach((externalId, code) -> jdbcTemplate.update("UPDATE job_posting SET occupation_code = "
                + "coalesce(?, occupation_code), occupation_checked = TRUE, title_occupation_code = CASE WHEN "
                + "CAST(? AS VARCHAR) IS NULL THEN title_occupation_code END WHERE source_id = ? AND external_id = ?",
                code, code, sourceId, externalId));
    }

    /**
     * Пора ли начинать полный обход: обходов ещё не было, последний не завершён (его цепочка оборвалась — вызывающий
     * проверяет, что она не идёт) или последний начался раньше {@code every} назад.
     *
     * @param every срок между началами обходов
     * @return пора ли
     */
    public boolean cycleDue(Duration every) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT coalesce((SELECT completed_at IS NULL OR started_at < now() - make_interval(secs => ?)
                                 FROM portal_cycle ORDER BY id DESC LIMIT 1), true)
                """, Boolean.class, (double) every.toSeconds()));
    }

    /**
     * @return номер нового полного обхода
     */
    public long startCycle() {
        return jdbcTemplate.queryForObject("INSERT INTO portal_cycle DEFAULT VALUES RETURNING id", Long.class);
    }

    /**
     * Отмечает обход завершённым.
     *
     * @param cycle номер обхода
     */
    public void completeCycle(long cycle) {
        jdbcTemplate.update("UPDATE portal_cycle SET completed_at = now() WHERE id = ?", cycle);
    }

    /**
     * IČO, уже известные у вакансий.
     *
     * @param externalIds id вакансий
     * @return id вакансии → IČO (только вакансии с определённым IČO)
     */
    public Map<String, String> knownNumbers(List<String> externalIds) {
        Map<String, String> numbers = new HashMap<>();
        jdbcTemplate.query("""
                SELECT external_id, registration_number FROM portal_offer
                WHERE external_id = ANY(?) AND registration_number IS NOT NULL
                """, (PreparedStatement statement) -> statement.setArray(1, textArray(statement, externalIds)),
                row -> {
                    numbers.put(row.getString(1), row.getString(2));
                });
        return numbers;
    }

    /**
     * IČO по названию из списка работодателей портала ({@code portal_employer}, §45) — только если название там одно.
     *
     * @param name название работодателя
     * @return IČO; пусто — названия нет или их несколько
     */
    public Optional<String> listedNumber(String name) {
        List<String> numbers = jdbcTemplate.queryForList(
                "SELECT registration_number FROM portal_employer WHERE name = ? LIMIT 2", String.class, name);
        return numbers.size() == 1 ? Optional.of(numbers.get(0)) : Optional.empty();
    }

    /**
     * IČO по названию, найденное поиском работодателей портала.
     *
     * @param name название работодателя
     * @return IČO; пусто — не искали или поиск не дал IČO
     */
    public Optional<String> searchedNumber(String name) {
        return jdbcTemplate.queryForList("""
                SELECT registration_number FROM portal_employer_name WHERE name = ? AND registration_number IS NOT NULL
                """, String.class, name).stream().findFirst();
    }

    /**
     * Искали ли название недавно без результата — тогда поиск не повторяется до срока.
     *
     * @param name         название работодателя
     * @param recheckAfter срок до повторного поиска
     * @return искали без результата позже, чем {@code recheckAfter} назад
     */
    public boolean searchedRecently(String name, Duration recheckAfter) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM portal_employer_name
                               WHERE name = ? AND checked_at > now() - make_interval(secs => ?))
                """, Boolean.class, name, (double) recheckAfter.toSeconds()));
    }

    /**
     * Записывает итог поиска работодателя по названию.
     *
     * @param name   название работодателя
     * @param number IČO; {@code null} — поиск не дал однозначного IČO
     */
    public void saveSearched(String name, String number) {
        jdbcTemplate.update("""
                INSERT INTO portal_employer_name (name, registration_number) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET registration_number = EXCLUDED.registration_number, checked_at = now()
                """, name, number);
    }

    /**
     * Записывает вакансии страницы: новые добавляются, известные обновляются; IČO не затирается пустым.
     *
     * @param offers  вакансии страницы
     * @param numbers id вакансии → IČO (нет — не определён)
     */
    public void saveOffers(List<PortalOffer> offers, Map<String, String> numbers) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO portal_offer (external_id, employer_name, registration_number, title, place, url)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (external_id) DO UPDATE SET
                    employer_name = EXCLUDED.employer_name,
                    registration_number = coalesce(EXCLUDED.registration_number, portal_offer.registration_number),
                    title = EXCLUDED.title, place = EXCLUDED.place, url = EXCLUDED.url, last_seen_at = now()
                """, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        PortalOffer offer = offers.get(index);
                        statement.setString(1, offer.externalId());
                        statement.setString(2, offer.employerName());
                        statement.setString(3, numbers.get(offer.externalId()));
                        statement.setString(4, offer.title());
                        statement.setString(5, offer.place());
                        statement.setString(6, offer.url());
                    }

                    @Override
                    public int getBatchSize() {
                        return offers.size();
                    }
                });
    }

    /**
     * Отмечает в обходе пропущенную страницу (портал её стабильно не отдаёт): обход станет неполным.
     *
     * @param cycle номер обхода
     */
    public void skipPage(long cycle) {
        jdbcTemplate.update("UPDATE portal_cycle SET skipped_pages = skipped_pages + 1 WHERE id = ?", cycle);
    }

    /**
     * @param cycle номер обхода
     * @return пропущены ли в обходе страницы (обход неполный)
     */
    public boolean hasSkippedPages(long cycle) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT skipped_pages > 0 FROM portal_cycle WHERE id = ?", Boolean.class, cycle));
    }

    /**
     * IČO работодателей, у которых вакансия из {@code externalIds} определена, но ещё не записана в их источник портала
     * (провайдер портала, доска — IČO): для ленты «новые сверху». Вакансия, IČO которой определился не в тот час,
     * когда она появилась, записывается с ближайшей лентой, пока она на первой странице. Вакансия со ссылкой не
     * http(s) не берётся: запись публикаций её пропускает, и лента писала бы работодателю каждый час (§109). Вакансии
     * без IČO ({@link #NAME_BOARD}, §258) лентой не пишутся — по итогу обхода.
     *
     * @param externalIds id вакансий страницы
     * @return IČO работодателей
     */
    public Set<String> unrecordedNumbers(List<String> externalIds) {
        return new HashSet<>(jdbcTemplate.query("""
                SELECT DISTINCT o.registration_number FROM portal_offer o
                WHERE o.external_id = ANY(?) AND o.registration_number IS NOT NULL AND o.url ~* '^https?://'
                  AND NOT EXISTS (SELECT 1 FROM job_posting p JOIN source s ON s.id = p.source_id
                                  WHERE s.provider = ? AND s.board = o.registration_number
                                    AND p.external_id = o.external_id)
                """, (PreparedStatement statement) -> {
                    statement.setArray(1, textArray(statement, externalIds));
                    statement.setString(2, StatePortalAdapter.PROVIDER);
                }, (row, number) -> row.getString(1)));
    }

    /**
     * Номер последнего обхода, завершённого или идущего.
     *
     * @return номер; пусто — обходов не было
     */
    public Optional<Long> lastCycle() {
        return jdbcTemplate.queryForList("SELECT max(id) FROM portal_cycle", Long.class).stream()
                .filter(Objects::nonNull).findFirst();
    }

    /**
     * Работодатели с вакансиями обхода, которым подключается источник портала (бизнес-описание §4.1, все каналы
     * компании, §260): IČO вакансии, которую видели с начала обхода, есть в реестре (страна — активная страна сбора),
     * компания действует и не кадровое агентство; свой источник компании (система найма, кадровая страница) портал не
     * отключает — одна вакансия двух каналов склеивается при записи ({@code PostingRecorder}). Прежде то же решал шаг
     * «проверка работодателей» (§45, удалён §107). Компания, уже связанная с этим источником портала, не повторяется.
     *
     * @param cycle номер обхода
     * @return работодатели с компанией
     */
    public List<NewEmployer> employersToConnect(long cycle) {
        return jdbcTemplate.query("""
                SELECT DISTINCT o.registration_number, c.id FROM portal_offer o
                JOIN company c ON c.country = 'SK'
                     AND c.registration_number IN (o.registration_number, ltrim(o.registration_number, '0'))
                JOIN collection_country cc ON cc.country = c.country AND cc.active
                WHERE o.last_seen_at >= (SELECT started_at FROM portal_cycle WHERE id = ?)
                  AND c.terminated_on IS NULL AND NOT c.agency
                  AND NOT EXISTS (SELECT 1 FROM company_source cs JOIN source s ON s.id = cs.source_id
                                  WHERE cs.company_id = c.id AND s.provider = ? AND s.board = o.registration_number)
                """, (row, number) -> new NewEmployer(row.getString(1), row.getLong(2)), cycle,
                StatePortalAdapter.PROVIDER);
    }

    /**
     * Подключает работодателю источник портала (доска — IČO) при записанном основании использования
     * ({@code source_permission}), связывает компанию с ним как работодателя, итог компании — «подключена»
     * ({@code company_check}). Одна транзакция; повторный вызов дублей не создаёт.
     *
     * @param employer работодатель с компанией
     */
    @Transactional
    public void connect(NewEmployer employer) {
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
     * Работодатели общего списка, которых нет в реестре (бизнес-описание §4.1, решение владельца 2026-11-03, §258): IČO
     * вакансии, которую видели с начала обхода, не найден среди компаний страны (в том числе предприниматели-физлица,
     * которых приём реестра не берёт, §24) или у вакансии IČO нет — доска {@link #NAME_BOARD} с названием из
     * объявления. Только работодатели без источника портала. Компания закрыта в реестре — работодатель в реестре есть,
     * сюда не входит.
     *
     * @param cycle номер обхода
     * @return доска и название работодателя из объявления
     */
    public List<UnregisteredEmployer> employersWithoutCompany(long cycle) {
        return jdbcTemplate.query("""
                SELECT o.board, min(o.employer_name) FROM (
                    SELECT coalesce(o.registration_number, %s) AS board, left(o.employer_name, 300) AS employer_name
                    FROM portal_offer o
                    WHERE o.last_seen_at >= (SELECT started_at FROM portal_cycle WHERE id = ?)
                      AND EXISTS (SELECT 1 FROM collection_country cc WHERE cc.country = 'SK' AND cc.active)
                      AND NOT EXISTS (SELECT 1 FROM company c WHERE c.country = 'SK'
                                      AND c.registration_number IN (o.registration_number,
                                                                    ltrim(o.registration_number, '0')))) o
                WHERE NOT EXISTS (SELECT 1 FROM source s WHERE s.provider = ? AND s.board = o.board)
                GROUP BY o.board
                """.formatted(NAME_BOARD_OF.formatted("o.employer_name")),
                (row, number) -> new UnregisteredEmployer(row.getString(1), row.getString(2)), cycle,
                StatePortalAdapter.PROVIDER);
    }

    /**
     * Подключает работодателю вне реестра источник портала без компании при записанном основании использования
     * ({@code source_permission}); работодатель на экране — название из объявления ({@code source.employer_name},
     * как у источников profesia.sk, V86). Появится компания с тем же IČO в реестре — {@link #connect} свяжет её с этим
     * источником. Повторный вызов дублей не создаёт.
     *
     * @param employer доска и название работодателя
     */
    public void connectWithoutCompany(UnregisteredEmployer employer) {
        jdbcTemplate.update("""
                INSERT INTO source (provider, board, country, employer_name) SELECT ?, ?, 'SK', ?
                WHERE EXISTS (SELECT 1 FROM source_permission WHERE scope = 'PROVIDER' AND provider = ?
                              AND decision = 'ALLOW')
                ON CONFLICT (provider, board) DO NOTHING
                """, StatePortalAdapter.PROVIDER, employer.board(), employer.name(), StatePortalAdapter.PROVIDER);
    }

    /**
     * Публикации источника портала из общего списка: вакансии работодателя, которые видели с начала обхода (полным
     * обходом или лентой). Доска {@link #NAME_BOARD} — вакансии без IČO с этим названием работодателя (§258): нашёлся
     * IČO позже — вакансия уходит в источник с IČO, здесь закрывается по общему правилу.
     *
     * @param board доска источника: IČO работодателя или {@link #NAME_BOARD} и название
     * @param cycle номер обхода
     * @return публикации без текста (деталь не читается — отбор по названию, как прежде)
     */
    public List<FetchedPosting> postingsOf(String board, long cycle) {
        String employer = board.startsWith(NAME_BOARD)
                ? "registration_number IS NULL AND " + NAME_BOARD_OF.formatted("employer_name") + " = ?"
                : "registration_number = ?";
        return jdbcTemplate.query("""
                SELECT external_id, title, url, place FROM portal_offer
                WHERE %s AND last_seen_at >= (SELECT started_at FROM portal_cycle WHERE id = ?)
                ORDER BY external_id
                """.formatted(employer), (row, number) -> new FetchedPosting(row.getString(1), row.getString(2),
                        row.getString(3), row.getString(4), null), board, cycle);
    }

    /**
     * Работодатель общего списка вне реестра (§258).
     *
     * @param board доска источника портала: IČO или {@link #NAME_BOARD} и название
     * @param name  название работодателя из объявления
     */
    public record UnregisteredEmployer(String board, String name) {
    }

    /**
     * Работодатель общего списка, найденный в реестре.
     *
     * @param registrationNumber IČO
     * @param companyId          компания реестра
     */
    public record NewEmployer(String registrationNumber, long companyId) {
    }

    private static Array textArray(PreparedStatement statement, List<String> values) throws SQLException {
        return statement.getConnection().createArrayOf("text", values.toArray());
    }
}
