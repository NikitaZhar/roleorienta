package com.roleorienta.worker.crawl;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Ссылка обхода на снимок исходного ответа источника в S3-хранилище (технический документ §4,
 * §16.10). Создаётся только обходом ({@link CrawlRun#addSnapshot}); время сохранения ставит БД.
 */
@Entity
@Table(name = "source_snapshot")
public class SourceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crawl_run_id")
    private CrawlRun crawlRun;

    private String objectKey;

    private String sha256;

    /**
     * Для JPA.
     */
    protected SourceSnapshot() {
    }

    SourceSnapshot(CrawlRun crawlRun, String objectKey, String sha256) {
        this.crawlRun = crawlRun;
        this.objectKey = objectKey;
        this.sha256 = sha256;
    }
}
