package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.CrawlRun;
import com.roleorienta.core.domain.CrawlRunState;
import com.roleorienta.core.domain.CrawlTask;
import com.roleorienta.core.domain.CrawlTaskState;
import com.roleorienta.core.domain.Source;
import com.roleorienta.worker.scheduling.CrawlRunRepository;
import com.roleorienta.worker.scheduling.CrawlTaskRepository;
import com.roleorienta.worker.scheduling.SourceRepository;
import org.springframework.stereotype.Component;

/**
 * Учёт обхода для обработчика {@code DISCOVER_PAGE}: чтение источника и обхода по
 * идентификаторам задания и отметка итога обхода и задания. Вынесено из
 * {@link DiscoverPageJobHandler} (§78): иначе у него 10 зависимостей (контракт §3.10).
 * Вызывается в транзакции обработчика.
 */
@Component
public class CrawlBookkeeping {

    private final SourceRepository sourceRepository;
    private final CrawlRunRepository crawlRunRepository;
    private final CrawlTaskRepository crawlTaskRepository;

    /**
     * @param sourceRepository    источники
     * @param crawlRunRepository  обходы
     * @param crawlTaskRepository задания
     */
    public CrawlBookkeeping(SourceRepository sourceRepository, CrawlRunRepository crawlRunRepository,
                            CrawlTaskRepository crawlTaskRepository) {
        this.sourceRepository = sourceRepository;
        this.crawlRunRepository = crawlRunRepository;
        this.crawlTaskRepository = crawlTaskRepository;
    }

    /**
     * @param sourceId идентификатор источника
     * @return источник
     * @throws IllegalStateException если источника нет
     */
    public Source requireSource(Long sourceId) {
        return sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalStateException("Источник не найден: id=" + sourceId));
    }

    /**
     * @param crawlRunId идентификатор обхода
     * @return обход
     * @throws IllegalStateException если обхода нет
     */
    public CrawlRun requireRun(Long crawlRunId) {
        return crawlRunRepository.findById(crawlRunId)
                .orElseThrow(() -> new IllegalStateException("Обход не найден: id=" + crawlRunId));
    }

    /**
     * Отмечает итог: обход {@code COMPLETED}/{@code FAILED}, задание {@code SUCCEEDED}/{@code FAILED}.
     *
     * @param run       обход
     * @param taskId    идентификатор задания {@code DISCOVER_PAGE}
     * @param succeeded обход выполнен
     * @throws IllegalStateException если задания нет
     */
    public void finish(CrawlRun run, Long taskId, boolean succeeded) {
        run.setState(succeeded ? CrawlRunState.COMPLETED : CrawlRunState.FAILED);
        crawlRunRepository.save(run);
        CrawlTask task = crawlTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Задание не найдено: id=" + taskId));
        task.setState(succeeded ? CrawlTaskState.SUCCEEDED : CrawlTaskState.FAILED);
        crawlTaskRepository.save(task);
    }
}
