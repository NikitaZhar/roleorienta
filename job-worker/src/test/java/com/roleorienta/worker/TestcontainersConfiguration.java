package com.roleorienta.worker;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Тестовые зависимости job-worker в контейнерах (Testcontainers): PostgreSQL, RabbitMQ и SeaweedFS тех же версий,
 * что в docker-compose. Нужен запущенный Docker.
 *
 * <p><b>Один набор контейнеров на весь прогон тестов</b> (шаблон «singleton containers», аудит §154). Контейнеры —
 * статические поля, запускаются один раз при первой загрузке класса и общие для всех контекстов Spring тестов.
 * Прежде они были бинами контекста: каждый из ~10 разных контекстов (свои свойства, подмены бинов) поднимал свой
 * набор, кэш контекстов держал их до конца прогона — до ~30 контейнеров одновременно, и под этой нагрузкой брокер не
 * укладывался в сроки тестов (5 с): падения в тестах, не связанных с правкой. Контейнеры не бины — закрытие контекста
 * их не останавливает; удаляет их служебный контейнер Testcontainers (Ryuk) в конце прогона.
 * https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/#singleton-containers</p>
 *
 * <p><b>Каждому контексту — свои данные</b> в общих контейнерах: своя база в PostgreSQL, свой виртуальный хост в
 * RabbitMQ (отдельный набор очередей и обменников), своя корзина в SeaweedFS. Так изоляция та же, что была при
 * своих контейнерах: классы тестов чистят свои таблицы только перед собой, и в общей базе строки одного класса
 * мешали бы очистке другого через внешние ключи (повторная проверка аудита §154). Адреса передаются в контекст через
 * {@link DynamicPropertyRegistrar} (свойства с наивысшим приоритетом); бин создаётся один раз на контекст.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String SEAWEEDFS_IMAGE = "chrislusf/seaweedfs:4.48";
    private static final String S3_ACCESS_KEY = "roleorienta";
    private static final String S3_SECRET_KEY = "roleorienta-secret";
    private static final int S3_PORT = 8333;

    /**
     * Предел соединений общего PostgreSQL: каждый кэшированный контекст держит свой пул (до 10 соединений), контекстов
     * в прогоне больше десятка, а по умолчанию предел — 100 («too many clients already»). {@code fsync=off} — как у
     * контейнера Testcontainers по умолчанию (команда заменяется целиком): для тестов надёжность записи не нужна.
     */
    private static final String MAX_CONNECTIONS = "max_connections=400";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "fsync=off", "-c", MAX_CONNECTIONS);

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));

    /** Номер следующего контекста: имя его базы, виртуального хоста и корзины. */
    private static final AtomicInteger CONTEXTS = new AtomicInteger();

    /**
     * SeaweedFS — хранилище снимков: образ тот же, что в docker-compose; запускает {@code weed mini} (все части одним
     * процессом, S3-шлюз на 8333). Доступ — {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}; готовность —
     * {@code /healthz} S3-шлюза. Готового модуля Testcontainers для SeaweedFS нет — обычный контейнер.
     */
    private static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(DockerImageName.parse(SEAWEEDFS_IMAGE))
            .withEnv("AWS_ACCESS_KEY_ID", S3_ACCESS_KEY)
            .withEnv("AWS_SECRET_ACCESS_KEY", S3_SECRET_KEY)
            .withExposedPorts(S3_PORT)
            .waitingFor(Wait.forHttp("/healthz").forPort(S3_PORT));

    static {
        Startables.deepStart(POSTGRES, RABBIT, SEAWEEDFS).join();
    }

    /**
     * Своя база, виртуальный хост и корзина контекста и их адреса в свойствах: PostgreSQL ({@code spring.datasource.*}),
     * RabbitMQ ({@code spring.rabbitmq.*}), SeaweedFS ({@code app.snapshot.*}).
     *
     * @return регистратор свойств тестового контекста
     */
    @Bean
    public DynamicPropertyRegistrar containerProperties() {
        String name = "ctx" + CONTEXTS.incrementAndGet();
        createDatabase(name);
        createVirtualHost(name);
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
        return registry -> {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.rabbitmq.host", RABBIT::getHost);
            registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
            registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
            registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
            registry.add("spring.rabbitmq.virtual-host", () -> name);
            registry.add("app.snapshot.endpoint",
                    () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(S3_PORT));
            registry.add("app.snapshot.bucket", () -> "snapshots-" + name);
            registry.add("app.snapshot.access-key", () -> S3_ACCESS_KEY);
            registry.add("app.snapshot.secret-key", () -> S3_SECRET_KEY);
        };
    }

    /** Пустая база контекста в общем PostgreSQL; схему в ней создаёт Flyway контекста. */
    private static void createDatabase(String name) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException failure) {
            throw new IllegalStateException("Test database " + name + " is not created", failure);
        }
    }

    /** Виртуальный хост контекста в общем RabbitMQ с полными правами администратора контейнера. */
    private static void createVirtualHost(String name) {
        rabbitctl("add_vhost", name);
        rabbitctl("set_permissions", "-p", name, RABBIT.getAdminUsername(), ".*", ".*", ".*");
    }

    private static void rabbitctl(String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = "rabbitmqctl";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        try {
            if (RABBIT.execInContainer(command).getExitCode() != 0) {
                throw new IllegalStateException("rabbitmqctl " + String.join(" ", arguments) + " failed");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("rabbitmqctl " + String.join(" ", arguments) + " failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: rabbitmqctl " + String.join(" ", arguments), interrupted);
        }
    }
}
