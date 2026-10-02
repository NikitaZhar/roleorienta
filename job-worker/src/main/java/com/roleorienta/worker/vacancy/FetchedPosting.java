package com.roleorienta.worker.vacancy;

/**
 * Публикация в общем виде, как её прочитал адаптер источника. Название и место длиннее поля хранения
 * (500 символов) обрезаются: одно длинное объявление (Workday склеивает все места вакансии) не должно
 * ронять чтение всего источника.
 *
 * @param externalId id публикации у источника
 * @param title      позиция
 * @param url        ссылка на публикацию
 * @param location   место работы как указано источником; {@code null} — не получено (сохранённое место
 *                   не меняется)
 * @param content    текст публикации; {@code null} — не получен
 */
public record FetchedPosting(String externalId, String title, String url, String location, String content) {

    /** Длина полей title и location в job_posting, vacancy и vacancy_revision. */
    public static final int MAX_TEXT = 500;

    /**
     * Обрезка названия и места до {@link #MAX_TEXT}.
     */
    public FetchedPosting {
        title = cut(title);
        location = cut(location);
    }

    private static String cut(String value) {
        return value == null || value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }
}
