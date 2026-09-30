package com.roleorienta.worker.vacancy;

/**
 * Публикация в общем виде, как её прочитал адаптер источника.
 *
 * @param externalId id публикации у источника
 * @param title      позиция
 * @param url        ссылка на публикацию
 * @param location   место работы как указано источником; {@code null} — не получено (сохранённое место
 *                   не меняется)
 * @param content    текст публикации; {@code null} — не получен
 */
public record FetchedPosting(String externalId, String title, String url, String location, String content) {
}
