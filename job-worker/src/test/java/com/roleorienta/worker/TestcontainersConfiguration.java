package com.roleorienta.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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

    private static final String MINIO_IMAGE = "bitnamilegacy/minio:2025.2.28-debian-12-r1";
    private static final String MINIO_USER = "roleorienta";
    private static final String MINIO_PASSWORD = "roleorienta-secret";
    private static final int MINIO_PORT = 9000;

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
     * Контейнер MinIO — хранилище снимков для интеграционных тестов. Образ тот же, что в
     * docker-compose (архивный Bitnami: образы MinIO убраны из реестров; замена — в
     * {@code docs/next-step.md}, «Долг»). Готовность — по health-адресу MinIO.
     *
     * @return контейнер
     */
    @Bean
    public GenericContainer<?> minioContainer() {
        return new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withExposedPorts(MINIO_PORT)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(MINIO_PORT));
    }

    /**
     * Адрес и доступ MinIO в настройках {@code app.snapshot.*}.
     *
     * @param minio контейнер MinIO
     * @return регистратор свойств тестового контекста
     */
    @Bean
    public DynamicPropertyRegistrar snapshotProperties(GenericContainer<?> minio) {
        return registry -> {
            registry.add("app.snapshot.endpoint",
                    () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(MINIO_PORT));
            registry.add("app.snapshot.access-key", () -> MINIO_USER);
            registry.add("app.snapshot.secret-key", () -> MINIO_PASSWORD);
        };
    }
}
