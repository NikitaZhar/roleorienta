package com.roleorienta.worker.adapter;

import com.roleorienta.worker.crawl.PartialReason;
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
     * @param postings      публикации доски
     * @param partialReason причина неполного чтения; {@code null} — список прочитан полностью. Только
     *                      полное чтение может закрыть вакансию
     */
    record Read(List<FetchedPosting> postings, PartialReason partialReason) implements SourceReadResult {

        /**
         * @param postings публикации доски
         * @return полное чтение
         */
        public static Read full(List<FetchedPosting> postings) {
            return new Read(postings, null);
        }

        /**
         * @param postings публикации, прочитанные до остановки
         * @param reason   причина неполноты
         * @return неполное чтение
         */
        public static Read partial(List<FetchedPosting> postings, PartialReason reason) {
            return new Read(postings, reason);
        }

        /**
         * @return {@code true} — список прочитан полностью
         */
        public boolean complete() {
            return partialReason == null;
        }
    }

    /**
     * Источник не отдал список.
     *
     * @param failure временный или постоянный отказ
     */
    record Unavailable(HttpResult failure) implements SourceReadResult {
    }
}
