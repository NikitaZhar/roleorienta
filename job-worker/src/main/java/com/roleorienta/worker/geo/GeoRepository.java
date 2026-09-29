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
     * @return {@code true} — справочник загружен
     */
    public boolean isLoaded() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM geo_city)", Boolean.class));
    }

    /**
     * Заменяет справочник целиком одной транзакцией.
     *
     * @param cities города
     */
    @Transactional
    public void replace(Collection<GeoCity> cities) {
        jdbcTemplate.update("DELETE FROM geo_city");
        jdbcTemplate.batchUpdate("INSERT INTO geo_city (name, country, population) VALUES (?, ?, ?)", cities, 1000,
                (statement, city) -> {
                    statement.setString(1, city.name());
                    statement.setString(2, city.country());
                    statement.setLong(3, city.population());
                });
    }
}
