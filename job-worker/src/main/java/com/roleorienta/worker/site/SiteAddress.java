package com.roleorienta.worker.site;

/**
 * Адрес сайта для проверки: хост и путь стартовой страницы относительно главной.
 *
 * @param host хост
 * @param path путь без ведущей косой черты ({@code sk/}); пустой — главная
 */
record SiteAddress(String host, String path) {
}
