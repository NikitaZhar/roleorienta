package com.roleorienta.worker.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.task.TaskExecutor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Сценарий 4 (бизнес-описание §7.4) и сценарий 9 для мест обработки: страны сбора берутся из условий
 * пользователей, партии стран чередуются, у каждой страны своё место; страна без условий в очередь
 * не попадает. Реестры двух стран — заглушки по три партии.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CollectionAlternationTests.StubRegistries.class})
class CollectionAlternationTests {

    private static final String EMAIL = "collection@example.com";
    private static final String POSITION = "collection-test";
    private static final int MAX_TASK_ROUNDS = 20;

    /** Порядок прочитанных партий: страна и номер партии. */
    private static final List<String> BATCHES = Collections.synchronizedList(new ArrayList<>());

    @Autowired
    private CollectionPlanner planner;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StubRegistry austria;

    /**
     * Условия поиска других тестов не участвуют; места заглушек — с начала.
     */
    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("UPDATE search_condition SET active = FALSE");
        BATCHES.clear();
        StubRegistries.reset();
    }

    /**
     * Удаляет строки теста.
     */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM search_condition WHERE user_id IN (SELECT id FROM user_account WHERE email = ?)",
                EMAIL);
        jdbcTemplate.update("DELETE FROM user_account WHERE email = ?", EMAIL);
        jdbcTemplate.update("DELETE FROM position WHERE code = ?", POSITION);
        jdbcTemplate.update("DELETE FROM company_check WHERE company_id IN (SELECT id FROM company WHERE country IN ('AT', 'CZ'))");
        jdbcTemplate.update("DELETE FROM company WHERE country IN ('AT', 'CZ')");
        for (String table : new String[] {"collection_country", "outbox_event", "task"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    /**
     * Условия пользователя — Австрия и Чехия: партии идут AT, CZ, AT, CZ, AT, CZ — каждая страна
     * продолжает со своего места, чтение стран до конца; Венгрия без условий не читается.
     */
    @Test
    void batchesAlternateBetweenCountriesOfUserConditions() {
        condition("{AT,CZ}");

        planner.refreshAndEnqueue();
        runTasks();

        assertThat(BATCHES).containsExactly("AT0", "CZ0", "AT1", "CZ1", "AT2", "CZ2");
        assertThat(jdbcTemplate.queryForList("SELECT country || ':' || batches || ':' || has_more "
                + "FROM collection_country ORDER BY 1", String.class)).containsExactly("AT:3:false", "CZ:3:false");
    }

    /**
     * После остановки (задания нет, состояние — в БД) новый тик продолжает каждую страну с её места;
     * страна, снятая всеми пользователями, выходит из чередования.
     */
    @Test
    void placesSurviveRestartAndRemovedCountryLeavesQueue() {
        condition("{AT,CZ}");
        planner.refreshAndEnqueue();
        executeQueuedOnce();
        executeQueuedOnce();
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM task");
        BATCHES.clear();

        jdbcTemplate.update("UPDATE search_condition SET countries = '{AT}'");
        planner.refreshAndEnqueue();
        runTasks();

        assertThat(BATCHES).containsExactly("AT1", "AT2");
        assertThat(austria.place()).isEqualTo(3);
    }

    /**
     * Первичный обход страны завершён, когда реестр прочитан до конца и у каждой действующей компании
     * страны есть итог проверки: у Австрии итог есть у всех, у Чехии одна компания без итога.
     */
    @Test
    void firstPassIsDoneWhenRegistryIsReadAndEveryCompanyHasResult() {
        condition("{AT,CZ}");
        planner.refreshAndEnqueue();
        runTasks();
        jdbcTemplate.update("""
                INSERT INTO company (country, registration_number, name, registry) VALUES
                ('AT', 'FN1', 'Alpen GmbH', 'TEST'), ('CZ', 'CZ1', 'Brno s.r.o.', 'TEST')
                """);
        jdbcTemplate.update("""
                INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
                SELECT id, 'SITE_NOT_FOUND', now(), now() FROM company WHERE country = 'AT'
                """);

        planner.refreshAndEnqueue();

        assertThat(jdbcTemplate.queryForList("SELECT country FROM collection_country "
                + "WHERE first_pass_done_at IS NOT NULL", String.class)).containsExactly("AT");
    }

    private void condition(String countries) {
        long position = jdbcTemplate.queryForObject(
                "INSERT INTO position (code, name) VALUES (?, 'Test') RETURNING id", Long.class, POSITION);
        long user = jdbcTemplate.queryForObject(
                "INSERT INTO user_account (email, password_hash) VALUES (?, '-') RETURNING id", Long.class, EMAIL);
        jdbcTemplate.update("""
                INSERT INTO search_condition (user_id, countries, position_id, portion_limit, active)
                VALUES (?, ?::text[], ?, 20, TRUE)
                """, user, countries, position);
    }

    private void executeQueuedOnce() {
        jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id", Long.class)
                .forEach(executor::execute);
    }

    private void runTasks() {
        for (int round = 0; round < MAX_TASK_ROUNDS; round++) {
            List<Long> queued = jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id",
                    Long.class);
            if (queued.isEmpty()) {
                return;
            }
            queued.forEach(executor::execute);
        }
        throw new AssertionError("Collection did not finish in " + MAX_TASK_ROUNDS + " rounds");
    }

    /**
     * Реестр-заглушка: три партии, место — в памяти.
     */
    static final class StubRegistry implements CountryRegistry {

        private static final int TOTAL = 3;

        private final String country;
        private final AtomicInteger place = new AtomicInteger();

        StubRegistry(String country) {
            this.country = country;
        }

        @Override
        public String country() {
            return country;
        }

        @Override
        public boolean intakeBatch() {
            BATCHES.add(country + place.getAndIncrement());
            return place.get() < TOTAL;
        }

        int place() {
            return place.get();
        }
    }

    /**
     * Реестры Австрии и Чехии — заглушки.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubRegistries {

        private static final StubRegistry AUSTRIA = new StubRegistry("AT");
        private static final StubRegistry CZECHIA = new StubRegistry("CZ");

        static void reset() {
            AUSTRIA.place.set(0);
            CZECHIA.place.set(0);
        }

        /**
         * @return реестр Австрии
         */
        @Bean
        StubRegistry austria() {
            return AUSTRIA;
        }

        /**
         * @return реестр Чехии
         */
        @Bean
        StubRegistry czechia() {
            return CZECHIA;
        }
    }
}
