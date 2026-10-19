package com.roleorienta.worker;

import org.springframework.beans.factory.annotation.Qualifier;
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
 * <p>Поднимает одноразовые PostgreSQL, RabbitMQ и SeaweedFS тех же версий, что в docker-compose;
 * {@link ServiceConnection} сам подставляет адреса PostgreSQL и RabbitMQ в контекст Spring, адрес
 * SeaweedFS — {@link DynamicPropertyRegistrar} (для S3 готового подключения в Spring Boot нет). Нужен
 * запущенный Docker.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String SEAWEEDFS_IMAGE = "chrislusf/seaweedfs:4.48";
    private static final String S3_ACCESS_KEY = "roleorienta";
    private static final String S3_SECRET_KEY = "roleorienta-secret";
    private static final int S3_PORT = 8333;

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
     * Контейнер SeaweedFS — хранилище снимков для интеграционных тестов. Образ тот же, что в
     * docker-compose; запускает {@code weed mini} (все части SeaweedFS одним процессом, S3-шлюз на 8333).
     * Доступ — {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}; готовность — {@code /healthz}
     * S3-шлюза. Готового модуля Testcontainers для SeaweedFS нет — обычный контейнер.
     *
     * @return контейнер
     */
    @Bean
    public GenericContainer<?> seaweedfsContainer() {
        return new GenericContainer<>(DockerImageName.parse(SEAWEEDFS_IMAGE))
                .withEnv("AWS_ACCESS_KEY_ID", S3_ACCESS_KEY)
                .withEnv("AWS_SECRET_ACCESS_KEY", S3_SECRET_KEY)
                .withExposedPorts(S3_PORT)
                .waitingFor(Wait.forHttp("/healthz").forPort(S3_PORT));
    }

    /**
     * Адрес и доступ SeaweedFS в настройках {@code app.snapshot.*}. {@link Qualifier} выбирает бин
     * SeaweedFS по имени: контейнеры PostgreSQL и RabbitMQ — тоже {@code GenericContainer}.
     *
     * @param seaweedfs контейнер SeaweedFS
     * @return регистратор свойств тестового контекста
     */
    @Bean
    public DynamicPropertyRegistrar snapshotProperties(
            @Qualifier("seaweedfsContainer") GenericContainer<?> seaweedfs) {
        return registry -> {
            registry.add("app.snapshot.endpoint",
                    () -> "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(S3_PORT));
            registry.add("app.snapshot.access-key", () -> S3_ACCESS_KEY);
            registry.add("app.snapshot.secret-key", () -> S3_SECRET_KEY);
        };
    }
}
