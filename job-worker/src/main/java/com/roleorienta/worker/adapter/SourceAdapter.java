package com.roleorienta.worker.adapter;

import com.roleorienta.worker.vacancy.FetchedPosting;
import java.util.Optional;

/**
 * Адаптер формата кадровой страницы (технический документ §5, §16.6): один на провайдера,
 * обслуживает все доски этого провайдера. Реализации — бины Spring.
 */
public interface SourceAdapter {

    /**
     * @return код провайдера, совпадает с {@code source.provider}
     */
    String provider();

    /**
     * Читает список публикаций доски.
     *
     * @param board идентификатор доски у провайдера
     * @return публикации или отказ источника; исключений не бросает
     */
    SourceReadResult read(String board);

    /**
     * Читает список публикаций доски в области страны. По умолчанию провайдер фильтра не имеет и
     * читает всю доску.
     *
     * @param board   идентификатор доски у провайдера
     * @param country страна (ISO 3166-1 alpha-2); {@code null} — без фильтра
     * @return публикации или отказ источника; исключений не бросает
     */
    default SourceReadResult read(String board, String country) {
        return read(board);
    }

    /**
     * Есть ли у доски публикации в стране — одним дешёвым запросом, если провайдер это умеет
     * (Workday — фасет страны). По умолчанию провайдер не умеет: доску читают и смотрят места.
     *
     * @param board   идентификатор доски у провайдера
     * @param country страна (ISO 3166-1 alpha-2)
     * @return есть или нет; пусто — ответить так нельзя (нет фасета страны, запрос не удался)
     */
    default Optional<Boolean> hasPostingsIn(String board, String country) {
        return Optional.empty();
    }

    /**
     * Проверяет публикацию, пропавшую из списка, прочитанного с фильтром по стране. По умолчанию
     * провайдер фильтра не имеет: его список полный, пропажа из него — отсутствие.
     *
     * @param board      идентификатор доски у провайдера
     * @param externalId id публикации у провайдера
     * @return есть, нет или не удалось проверить; исключений не бросает
     */
    default PostingCheck check(String board, String externalId) {
        return new PostingCheck.Absent();
    }

    /**
     * Деталь публикации отдельным запросом — для провайдеров, у которых список не содержит текста
     * (и может не содержать мест: Workday отдаёт в списке сводку «2 Locations»).
     *
     * @param board      идентификатор доски у провайдера
     * @param externalId id публикации у провайдера
     * @return публикация из детали: место ({@code null} — не указано) и текст ({@code null} — не
     *         получен); {@code null} — провайдер отдаёт всё в списке или запрос не удался
     */
    default FetchedPosting detail(String board, String externalId) {
        return null;
    }

    /**
     * Название работодателя, как его даёт система найма, — для источника без компании из реестра (доски обратного
     * пути, §27, §34; аудит §65). Один запрос; вызывается, пока название не записано.
     *
     * @param board      идентификатор доски у провайдера
     * @param externalId id одной из публикаций доски (Workday называет работодателя в детали публикации)
     * @return название; пусто — провайдер не называет или запрос не удался
     */
    default Optional<String> employerName(String board, String externalId) {
        return Optional.empty();
    }
}
