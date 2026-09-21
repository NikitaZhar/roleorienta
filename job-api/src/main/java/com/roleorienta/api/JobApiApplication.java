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
// поэтому явно указываем JPA, где их искать. Сущности-концерны только job-api
// (worker их не использует) живут в самом приложении, а не в core, и их пакеты тоже
// добавлены в область сканирования: com.roleorienta.api.auth (AppUser, §29) и
// com.roleorienta.api.saved (SavedPosting — персональные маркеры, §30).
@EntityScan({"com.roleorienta.core.domain", "com.roleorienta.api.auth", "com.roleorienta.api.saved", "com.roleorienta.api.subscription", "com.roleorienta.api.application"})
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
