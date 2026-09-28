package com.roleorienta.worker.adapter;

import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.util.List;

/**
 * Результат чтения доски адаптером.
 */
public sealed interface SourceReadResult {

    /**
     * Список публикаций прочитан.
     *
     * @param postings публикации доски
     * @param complete список прочитан полностью; только полное чтение может закрыть вакансию
     */
    record Read(List<FetchedPosting> postings, boolean complete) implements SourceReadResult {
    }

    /**
     * Источник не отдал список.
     *
     * @param failure временный или постоянный отказ
     */
    record Unavailable(HttpResult failure) implements SourceReadResult {
    }
}
