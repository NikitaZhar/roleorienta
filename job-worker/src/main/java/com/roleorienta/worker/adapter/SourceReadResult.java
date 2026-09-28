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
     */
    record Read(List<FetchedPosting> postings) implements SourceReadResult {
    }

    /**
     * Источник не отдал список.
     *
     * @param failure временный или постоянный отказ
     */
    record Unavailable(HttpResult failure) implements SourceReadResult {
    }
}
