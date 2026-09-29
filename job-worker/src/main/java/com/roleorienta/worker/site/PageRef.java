package com.roleorienta.worker.site;

/**
 * Страница в архиве Common Crawl.
 *
 * @param host     хост сайта
 * @param url      адрес страницы
 * @param filename файл архива (WARC)
 * @param offset   смещение записи в файле
 * @param length   длина записи
 */
public record PageRef(String host, String url, String filename, long offset, int length) {
}
