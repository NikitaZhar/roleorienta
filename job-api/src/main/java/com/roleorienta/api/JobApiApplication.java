package com.roleorienta.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

/**
 * Точка входа приложения job-api.
 *
 * <p>Обслуживает пользовательские сценарии через REST: поиск и фильтры вакансий,
 * подписки, компании и техпрофили, отклики, заметки, профиль навыков и отчёты об
 * ошибках. На Этапе 1 запускается как самостоятельное Spring Boot приложение
 * поверх общей базы PostgreSQL; бизнес-логика добавляется отдельными задачами в
 * пределах согласованной области.</p>
 */
@SpringBootApplication
// Доменные сущности лежат в общем модуле core (пакет вне com.roleorienta.api),
// поэтому явно указываем JPA, где их искать. Сущность пользователя (AppUser) —
// концерн только job-api (worker её не использует), поэтому она в пакете
// com.roleorienta.api.auth, а не в core; этот пакет добавлен в область сканирования.
@EntityScan({"com.roleorienta.core.domain", "com.roleorienta.api.auth"})
public class JobApiApplication {

    /**
     * Запускает Spring Boot контекст приложения job-api.
     *
     * @param args аргументы командной строки, передаваемые в Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(JobApiApplication.class, args);
    }
}
