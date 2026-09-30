package com.roleorienta.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Точка входа приложения job-api.
 *
 * <p>job-api обслуживает пользователя через REST: учётные записи, условия поиска, накопленный
 * список, отметки, запросы на добавление компаний, показатели ядра. Владеет схемой общей базы
 * PostgreSQL и применяет её миграции Flyway при старте (технический документ §2, §16.1).</p>
 *
 * <p>{@link ConfigurationPropertiesScan} регистрирует записи настроек с
 * {@code @ConfigurationProperties} без отдельного перечисления.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JobApiApplication {

    /**
     * Запускает контекст Spring Boot приложения job-api.
     *
     * @param args аргументы командной строки, передаются Spring Boot без изменений
     */
    public static void main(String[] args) {
        SpringApplication.run(JobApiApplication.class, args);
    }
}
