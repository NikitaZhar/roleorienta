package com.roleorienta.worker.jobs;

/**
 * Обработчик заданий одного типа.
 *
 * <p>Отдельный интерфейс (не наследник {@link JobHandler}), чтобы такие обработчики
 * не были кандидатами для внедрения там, где нужен единственный {@link JobHandler}
 * (см. {@code JobMessageListener}). Слушатель сам собирает все {@code TypedJobHandler}
 * и направляет каждое сообщение тому, чей {@link #taskType()} совпадает с типом
 * задания; для типов без своего обработчика вызывается запасной {@link JobHandler}.
 * Так обработчики новых типов ({@code FETCH_POSTING}, {@code AGGREGATE_COMPANY_PROFILE}
 * и др., §6 техдока) добавляются отдельными бинами, не меняя слушатель.</p>
 */
public interface TypedJobHandler {

    /**
     * Тип задания, который обслуживает обработчик (значение {@code CrawlTaskType},
     * например {@code "DISCOVER_PAGE"}) — совпадает с {@code eventType} сообщения.
     *
     * @return имя типа задания
     */
    String taskType();

    /**
     * Обрабатывает задание. Контракт по исключениям, повторам и идемпотентности —
     * тот же, что у {@link JobHandler#handle(JobMessage)}.
     *
     * @param message разобранное задание
     */
    void handle(JobMessage message);
}
