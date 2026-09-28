package com.roleorienta.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Точка входа приложения job-worker.
 *
 * <p>job-worker выполняет фоновую работу: планирование под leader-lock, публикацию outbox в
 * RabbitMQ, общий сбор (компании, кадровые страницы, вакансии) и проходы выдачи (технический
 * документ §2). Схему БД не меняет — её ведёт job-api.</p>
 */
@SpringBootApplication
public class JobWorkerApplication {

    /**
     * Запускает контекст Spring Boot приложения job-worker.
     *
     * @param args аргументы командной строки, передаются Spring Boot без изменений
     */
    public static void main(String[] args) {
        SpringApplication.run(JobWorkerApplication.class, args);
    }
}
