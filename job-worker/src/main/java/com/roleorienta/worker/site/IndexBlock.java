package com.roleorienta.worker.site;

/**
 * Блок индекса Common Crawl: сжатый кусок файла индекса.
 *
 * @param seq    номер блока в скане обхода
 * @param file   файл индекса ({@code cdx-00123.gz})
 * @param offset смещение блока в файле
 * @param length длина блока в байтах
 */
public record IndexBlock(int seq, String file, long offset, int length) {
}
