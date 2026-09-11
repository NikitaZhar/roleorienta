package com.roleorienta.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Проверка загрузки контекста приложения job-api на реальной базе PostgreSQL
 * (через Testcontainers): подтверждает, что автоконфигурация, JPA и Flyway
 * согласованы и контекст успешно стартует.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobApiApplicationTests {

    /**
     * Контекст приложения должен успешно загружаться.
     */
    @Test
    void contextLoads() {
    }
}
