package com.roleorienta.worker.geo;

import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Справочник городов GeoNames в таблице {@code geo_city} (JDBC).
 */
@Repository
public class GeoRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public GeoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param rules версия правил загрузки
     * @return {@code true} — справочник загружен по этим правилам (§210)
     */
    public boolean isCurrent(String rules) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM geo_city) AND EXISTS (SELECT 1 FROM geo_import WHERE rules = ?)",
                Boolean.class, rules));
    }

    /**
     * Заменяет справочник целиком одной транзакцией: до её конца сопоставление читает прежний справочник, а не пустой.
     *
     * @param cities города
     * @param rules  версия правил загрузки
     */
    @Transactional
    public void replace(Collection<GeoCity> cities, String rules) {
        jdbcTemplate.update("INSERT INTO geo_import (id, rules) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET rules = "
                + "EXCLUDED.rules", rules);
        jdbcTemplate.update("DELETE FROM geo_city");
        jdbcTemplate.batchUpdate("INSERT INTO geo_city (name, country, population) VALUES (?, ?, ?)", cities, 1000,
                (statement, city) -> {
                    statement.setString(1, city.name());
                    statement.setString(2, city.country());
                    statement.setLong(3, city.population());
                });
    }
}
