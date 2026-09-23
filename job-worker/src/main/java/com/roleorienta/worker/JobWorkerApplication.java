package com.roleorienta.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.roleorienta.worker.discovery.DiscoveryHarvestProperties;
import com.roleorienta.worker.discovery.cc.CcHarvestProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа приложения job-worker.
 *
 * <p>Отвечает за фоновые процессы: планирование, обнаружение работодателей, сбор,
 * нормализацию, дедупликацию, ревизии, агрегацию техпрофилей и рассылку
 * уведомлений/дайджеста. Уже на Этапе 1 работает поверх общей базы PostgreSQL,
 * RabbitMQ и transactional outbox с самого начала (см. ADR-10): планирование
 * под leader-lock, публикация outbox-событий и идемпотентная обработка заданий.</p>
 */
@SpringBootApplication
@EnableScheduling
// Сущности лежат и в этом модуле (outbox, идемпотентность), и в общем модуле core
// (Source, CrawlRun, CrawlTask). Явно указываем JPA оба пакета для поиска сущностей.
@EntityScan({"com.roleorienta.worker", "com.roleorienta.core.domain"})
@EnableConfigurationProperties({DiscoveryHarvestProperties.class, CcHarvestProperties.class})
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
