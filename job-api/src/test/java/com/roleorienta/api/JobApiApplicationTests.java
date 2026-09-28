package com.roleorienta.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Контекст job-api поднимается на реальной PostgreSQL: автоконфигурация, JPA и Flyway согласованы.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobApiApplicationTests {

    /**
     * Контекст приложения загружается без ошибок.
     */
    @Test
    void contextLoads() {
    }
}
