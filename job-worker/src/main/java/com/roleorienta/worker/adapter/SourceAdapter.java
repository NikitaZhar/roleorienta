package com.roleorienta.worker.adapter;

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
     * Текст публикации отдельным запросом — для провайдеров, у которых список его не содержит.
     *
     * @param board      идентификатор доски у провайдера
     * @param externalId id публикации у провайдера
     * @return текст; {@code null} — провайдер отдаёт текст в списке или запрос не удался
     */
    default String content(String board, String externalId) {
        return null;
    }
}
