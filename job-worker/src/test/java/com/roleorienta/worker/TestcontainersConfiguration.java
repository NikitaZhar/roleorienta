package com.roleorienta.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Тестовая конфигурация Testcontainers для job-worker.
 *
 * <p>Поднимает одноразовые контейнеры PostgreSQL и RabbitMQ и связывает их с
 * контекстом Spring через {@link ServiceConnection}, чтобы интеграционные тесты
 * выполнялись на реальных зависимостях (см. технический документ, §13).</p>
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

    /**
     * Создаёт контейнер RabbitMQ для интеграционных тестов.
     *
     * @return контейнер RabbitMQ, автоматически связываемый с ConnectionFactory контекста
     */
    @Bean
    @ServiceConnection
    public RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));
    }
}
