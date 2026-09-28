package com.roleorienta.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Тестовые зависимости job-worker в контейнерах (Testcontainers).
 *
 * <p>Поднимает одноразовые PostgreSQL и RabbitMQ тех же версий, что в docker-compose;
 * {@link ServiceConnection} сам подставляет их адреса в контекст Spring. Нужен запущенный Docker.</p>
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

    /**
     * Контейнер RabbitMQ для интеграционных тестов.
     *
     * @return контейнер, связанный с ConnectionFactory тестового контекста
     */
    @Bean
    @ServiceConnection
    public RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));
    }
}
