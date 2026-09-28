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
}
