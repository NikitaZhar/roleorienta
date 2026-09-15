package com.roleorienta.worker.scheduling;

import com.roleorienta.core.domain.CrawlTask;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к фоновым заданиям для планировщика. Создание — через {@code save}
 * (идентификатор задания нужен для outbox-события).
 */
public interface CrawlTaskRepository extends JpaRepository<CrawlTask, Long> {
}
