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
