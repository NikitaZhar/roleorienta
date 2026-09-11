package com.roleorienta.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Проверка загрузки контекста приложения job-worker на реальной базе PostgreSQL
 * (через Testcontainers): подтверждает, что автоконфигурация и JPA согласованы и
 * контекст успешно стартует.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobWorkerApplicationTests {

    /**
     * Контекст приложения должен успешно загружаться.
     */
    @Test
    void contextLoads() {
    }
}
