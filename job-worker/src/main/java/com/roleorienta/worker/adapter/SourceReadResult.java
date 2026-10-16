package com.roleorienta.worker.adapter;

import com.roleorienta.worker.crawl.PartialReason;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
     * @param postedAt      даты размещения работодателем по внешнему id (§127; {@link PostedDates}) — только те,
     *                      что дал источник
     */
    record Read(List<FetchedPosting> postings, PartialReason partialReason, List<String> responses,
            Set<String> notRead, Map<String, Instant> postedAt) implements SourceReadResult {

        /**
         * Чтение без дат размещения.
         *
         * @param postings      публикации доски
         * @param partialReason причина неполного чтения; {@code null} — полное
         * @param responses     тела ответов
         * @param notRead       есть в списке, но не прочитаны
         */
        public Read(List<FetchedPosting> postings, PartialReason partialReason, List<String> responses,
                Set<String> notRead) {
            this(postings, partialReason, responses, notRead, Map.of());
        }

        /**
         * @param dates даты размещения по внешнему id
         * @return то же чтение с датами
         */
        public Read withPostedAt(Map<String, Instant> dates) {
            return new Read(postings, partialReason, responses, notRead, dates);
        }

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
