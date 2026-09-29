package com.roleorienta.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Тестовые зависимости job-worker в контейнерах (Testcontainers).
 *
 * <p>Поднимает одноразовые PostgreSQL, RabbitMQ и MinIO тех же версий, что в docker-compose;
 * {@link ServiceConnection} сам подставляет адреса PostgreSQL и RabbitMQ в контекст Spring, адрес
 * MinIO — {@link DynamicPropertyRegistrar} (для S3 готового подключения в Spring Boot нет). Нужен
 * запущенный Docker.</p>
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

    /**
     * Контейнер MinIO — хранилище снимков для интеграционных тестов.
     *
     * @return контейнер
     */
    @Bean
    public MinIOContainer minioContainer() {
        return new MinIOContainer(DockerImageName.parse("minio/minio:latest"));
    }

    /**
     * Адрес и доступ MinIO в настройках {@code app.snapshot.*}.
     *
     * @param minio контейнер MinIO
     * @return регистратор свойств тестового контекста
     */
    @Bean
    public DynamicPropertyRegistrar snapshotProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("app.snapshot.endpoint", minio::getS3URL);
            registry.add("app.snapshot.access-key", minio::getUserName);
            registry.add("app.snapshot.secret-key", minio::getPassword);
        };
    }
}
