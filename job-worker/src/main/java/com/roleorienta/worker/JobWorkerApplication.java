package com.roleorienta.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа приложения job-worker.
 *
 * <p>Отвечает за фоновые процессы: планирование, обнаружение работодателей, сбор,
 * нормализацию, дедупликацию, ревизии, агрегацию техпрофилей и рассылку
 * уведомлений/дайджеста. На Этапе 1 работает как периодический
 * планировщик/обработчик поверх общей базы PostgreSQL, без брокера сообщений;
 * RabbitMQ и transactional outbox вводятся на Этапе 2 (см. ADR-10).</p>
 */
@SpringBootApplication
@EnableScheduling
public class JobWorkerApplication {

    /**
     * Запускает Spring Boot контекст приложения job-worker.
     *
     * @param args аргументы командной строки, передаваемые в Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(JobWorkerApplication.class, args);
    }
}
