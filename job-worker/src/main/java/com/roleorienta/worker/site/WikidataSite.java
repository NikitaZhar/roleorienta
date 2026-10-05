package com.roleorienta.worker.site;

/**
 * Официальный сайт организации из Wikidata.
 *
 * @param registrationNumber словацкий IČO (P8174), восемь цифр
 * @param url                официальный сайт (P856)
 * @param item               адрес элемента Wikidata — доказательство
 */
record WikidataSite(String registrationNumber, String url, String item) {
}
