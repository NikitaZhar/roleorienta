package com.roleorienta.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Тестовая конфигурация Testcontainers для job-api.
 *
 * <p>Поднимает одноразовый контейнер PostgreSQL и связывает его с контекстом
 * Spring через {@link ServiceConnection}, чтобы интеграционные тесты выполнялись
 * на реальной базе данных (см. технический документ, §13).</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * Создаёт контейнер PostgreSQL для интеграционных тестов.
     *
     * @return контейнер PostgreSQL, автоматически связываемый с DataSource контекста
     */
    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
