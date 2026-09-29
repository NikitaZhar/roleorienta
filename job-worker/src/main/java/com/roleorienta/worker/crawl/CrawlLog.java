package com.roleorienta.worker.crawl;

import com.roleorienta.worker.source.Availability;
import com.roleorienta.worker.source.Source;
import com.roleorienta.worker.source.SourceRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Итог обхода: запись обхода и доступность источника (технический документ §4, §9) — в
 * транзакции записи публикаций. {@code Propagation.MANDATORY} — метод выполняется только внутри уже
 * открытой транзакции, поэтому итог обхода не может записаться отдельно от публикаций.
 * https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
 */
@Component
public class CrawlLog {

    private static final Logger LOG = LoggerFactory.getLogger(CrawlLog.class);

    private final CrawlRunRepository runs;
    private final SourceRepository sources;
    private final CrawlProperties properties;

    /**
     * @param runs       доступ к обходам
     * @param sources    доступ к источникам
     * @param properties срок до «недоступен»
     */
    public CrawlLog(CrawlRunRepository runs, SourceRepository sources, CrawlProperties properties) {
        this.runs = runs;
        this.sources = sources;
        this.properties = properties;
    }

    /**
     * Сохраняет обход с итогом и пересчитывает доступность его источника: ответ списка — доступен;
     * отказ — серия отказов (см. {@link Source#markFailure}).
     *
     * @param run обход с итогом ({@link CrawlRun#read} или {@link CrawlRun#fail}), ещё не сохранённый
     * @param now время записи — конец обхода
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(CrawlRun run, Instant now) {
        runs.save(run.finish(now));
        Source source = sources.findById(run.getSource().getId()).orElseThrow();
        Availability before = source.getAvailability();
        if (run.isFailed()) {
            source.markFailure(run.isTemporaryFailure(), now, properties.unavailableAfter());
        } else {
            source.markRead();
        }
        if (source.getAvailability() != before) {
            LOG.warn("Source {} availability {} -> {}", source.getId(), before, source.getAvailability());
        }
    }
}
