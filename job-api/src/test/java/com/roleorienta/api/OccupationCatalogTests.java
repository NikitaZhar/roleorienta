package com.roleorienta.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Справочник профессий ISCO-08 / ESCO (миграция {@code V75}, стенограмма §202) загружен целиком и связан: группы ISCO-08
 * по уровням, названия подразделов и групп на четырёх языках, профессии ESCO с синонимами.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OccupationCatalogTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** ISCO-08: 10 разделов, 43 подраздела, 130 малых групп, 436 групп; у каждой, кроме раздела, — родитель. */
    @Test
    void iscoGroupsByLevel() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT count(*) FROM occupation_group GROUP BY level ORDER BY level", Long.class))
                .containsExactly(10L, 43L, 130L, 436L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_code FROM occupation_group WHERE code = '8332'", String.class)).isEqualTo("833");
    }

    /** Подразделы и группы названы на английском, словацком, немецком и русском. */
    @Test
    void shownGroupsAreNamedInFourLanguages() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM occupation_group g
                WHERE g.level IN (2, 4)
                  AND (SELECT count(*) FROM occupation_group_name n WHERE n.code = g.code) < 4
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM occupation_group_name WHERE code = '8322' AND language = 'ru'", String.class))
                .isEqualTo("Водители легковых автомобилей, такси и фургонов");
    }

    /** Синоним профессии ESCO ведёт к группе ISCO-08: «vodič nákladného vozidla» — водители грузовиков, 8332. */
    @Test
    void escoLabelLeadsToIscoGroup() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT o.group_code FROM esco_label l JOIN esco_occupation o ON o.id = l.occupation_id
                WHERE l.language = 'sk' AND l.label = 'vodič nákladného vozidla'
                """, String.class)).containsExactly("8332");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM esco_occupation", Long.class)).isEqualTo(3039L);
    }
}
