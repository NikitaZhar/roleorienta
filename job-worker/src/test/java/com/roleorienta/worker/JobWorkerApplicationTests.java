package com.roleorienta.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Проверка загрузки контекста приложения job-worker на реальной базе PostgreSQL
 * (через Testcontainers): подтверждает, что автоконфигурация и JPA согласованы и
 * контекст успешно стартует. Обход Common Crawl (по умолчанию включён, §72) здесь выключен:
 * тест не должен ходить в интернет.
 */
@SpringBootTest(properties = {"app.discovery.cc.enabled=false", "app.coverage.enabled=false"})
@Import(TestcontainersConfiguration.class)
class JobWorkerApplicationTests {

    /**
     * Контекст приложения должен успешно загружаться.
     */
    @Test
    void contextLoads() {
    }
}
