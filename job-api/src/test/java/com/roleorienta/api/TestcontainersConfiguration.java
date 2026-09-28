package com.roleorienta.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Тестовые зависимости job-api в контейнерах (Testcontainers).
 *
 * <p>Поднимает одноразовую PostgreSQL той же версии, что в docker-compose; {@link ServiceConnection}
 * сам подставляет её адрес и учётные данные в контекст Spring. Нужен запущенный Docker.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * Контейнер PostgreSQL для интеграционных тестов.
     *
     * @return контейнер, связанный с DataSource тестового контекста
     */
    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
