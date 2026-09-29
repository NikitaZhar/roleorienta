package com.roleorienta.worker.crawl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к обходам.
 */
public interface CrawlRunRepository extends JpaRepository<CrawlRun, Long> {
}
