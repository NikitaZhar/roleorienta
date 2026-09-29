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
     * @param responses     тела успешных ответов списка в порядке получения — для снимков
     */
    record Read(List<FetchedPosting> postings, PartialReason partialReason, List<String> responses)
            implements SourceReadResult {

        /**
         * @param postings  публикации доски
         * @param responses тела ответов
         * @return полное чтение
         */
        public static Read full(List<FetchedPosting> postings, List<String> responses) {
            return new Read(postings, null, responses);
        }

        /**
         * @param postings  публикации, прочитанные до остановки
         * @param reason    причина неполноты
         * @param responses тела ответов
         * @return неполное чтение
         */
        public static Read partial(List<FetchedPosting> postings, PartialReason reason, List<String> responses) {
            return new Read(postings, reason, responses);
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
