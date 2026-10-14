package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.adapter.stateportal.PortalOffer;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Общий список вакансий портала в БД (стенограмма §105): полные обходы ({@code portal_cycle}), вакансии
 * ({@code portal_offer}) и IČO по названию работодателя ({@code portal_employer_name}).
 */
@Repository
public class PortalListRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public PortalListRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
     * @param cycle   номер полного обхода; {@code null} — лента «новые сверху» (номер обхода у вакансии не меняется)
     */
    public void saveOffers(List<PortalOffer> offers, Map<String, String> numbers, Long cycle) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO portal_offer (external_id, employer_name, registration_number, title, place, url,
                                          last_seen_cycle)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (external_id) DO UPDATE SET
                    employer_name = EXCLUDED.employer_name,
                    registration_number = coalesce(EXCLUDED.registration_number, portal_offer.registration_number),
                    title = EXCLUDED.title, place = EXCLUDED.place, url = EXCLUDED.url, last_seen_at = now(),
                    last_seen_cycle = coalesce(EXCLUDED.last_seen_cycle, portal_offer.last_seen_cycle)
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
                        if (cycle == null) {
                            statement.setNull(7, Types.BIGINT);
                        } else {
                            statement.setLong(7, cycle);
                        }
                    }

                    @Override
                    public int getBatchSize() {
                        return offers.size();
                    }
                });
    }

    private static Array textArray(PreparedStatement statement, List<String> values) throws SQLException {
        return statement.getConnection().createArrayOf("text", values.toArray());
    }
}
