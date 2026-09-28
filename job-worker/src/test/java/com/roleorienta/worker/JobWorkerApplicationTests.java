package com.roleorienta.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Контекст job-worker поднимается на реальных PostgreSQL и RabbitMQ.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobWorkerApplicationTests {

    /**
     * Контекст приложения загружается без ошибок.
     */
    @Test
    void contextLoads() {
    }
}
