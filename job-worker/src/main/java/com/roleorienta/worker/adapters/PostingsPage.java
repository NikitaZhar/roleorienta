package com.roleorienta.worker.adapters;

import java.util.List;
import java.util.Map;

/**
 * Одна страница результата перечисления публикаций (§5 техдока: «перечислить
 * публикации в области источника с пагинацией»).
 *
 * <p>{@code nextCursor} — непрозрачный указатель на следующую страницу в терминах
 * конкретного адаптера, либо {@code null}, если страниц больше нет. Значение курсора
 * трактует только адаптер, который его выдал; вызывающая сторона лишь передаёт его
 * обратно, не разбирая. Для лент, отдающих весь список одним ответом (напр. доска
 * Greenhouse), страница одна и {@code nextCursor} всегда {@code null}.</p>
 *
 *
 * <p>{@code countryCounts} — распределение <b>всех</b> публикаций источника по странам, если
 * провайдер его отдаёт (Workday — фасет {@code Location_Country}, §56); ключ — название
 * страны на английском, значение — число публикаций. Пустая карта — провайдер распределения
 * не сообщает (напр. Greenhouse): гейт рынка тогда не применяется.</p>
 *
 * @param postings      публикации на этой странице
 * @param nextCursor    курсор следующей страницы или {@code null}, если это последняя страница
 * @param countryCounts публикации источника по странам; пусто — неизвестно
 */
public record PostingsPage(List<DiscoveredPosting> postings, String nextCursor,
                           Map<String, Integer> countryCounts) {

    public PostingsPage {
        countryCounts = countryCounts == null ? Map.of() : Map.copyOf(countryCounts);
    }

    /**
     * Страница без сведений о странах (провайдер их не сообщает).
     *
     * @param postings   публикации на этой странице
     * @param nextCursor курсор следующей страницы или {@code null}
     */
    public PostingsPage(List<DiscoveredPosting> postings, String nextCursor) {
        this(postings, nextCursor, Map.of());
    }
}
