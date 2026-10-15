package com.roleorienta.worker.adapter;

import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.util.List;
import java.util.Set;

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
     * @param notRead       внешние id публикаций, которые есть в списке, но в это чтение не прочитаны (окно по
     *                      кругу {@code JobPosting}, аудит §121): их известные публикации не подтверждаются, не
     *                      теряют подтверждение и не засчитываются отсутствующими (и при неполном чтении). Пусто —
     *                      прочитано всё, что в списке
     */
    record Read(List<FetchedPosting> postings, PartialReason partialReason, List<String> responses,
            Set<String> notRead) implements SourceReadResult {

        /**
         * @param postings  публикации доски
         * @param responses тела ответов
         * @return полное чтение
         */
        public static Read full(List<FetchedPosting> postings, List<String> responses) {
            return new Read(postings, null, responses, Set.of());
        }

        /**
         * @param postings  публикации, прочитанные до остановки
         * @param reason    причина неполноты
         * @param responses тела ответов
         * @return неполное чтение
         */
        public static Read partial(List<FetchedPosting> postings, PartialReason reason, List<String> responses) {
            return new Read(postings, reason, responses, Set.of());
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
