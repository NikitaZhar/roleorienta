package com.roleorienta.worker.adapter.stateportal;

import java.util.regex.Pattern;

/**
 * Вакансия общего списка государственного портала ({@code /search/ponuky}, стенограмма §104). Название и место
 * длиннее поля хранения (500 символов) обрезаются, как у {@link com.roleorienta.worker.vacancy.FetchedPosting}.
 *
 * @param externalId   id публикации, как у источника портала: uuid своей вакансии портала, {@code profesia:<номер>}
 *                     или адрес объявления другой площадки
 * @param employerName название работодателя, как его даёт портал (IČO в списке нет)
 * @param title        позиция — с пометкой «(muž/žena)», как в карточке портала
 * @param place        место работы; {@code null} — нет
 * @param url          ссылка: страница вакансии на портале или объявление площадки
 */
public record PortalOffer(String externalId, String employerName, String title, String place, String url) {

    /** Длина полей названия, места и работодателя в {@code portal_offer}. */
    public static final int MAX_TEXT = 500;

    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f-]{27}");

    /**
     * Обрезка названия, места и работодателя до {@link #MAX_TEXT}.
     */
    public PortalOffer {
        employerName = cut(employerName);
        title = cut(title);
        place = cut(place);
    }

    /**
     * @return своя вакансия портала (есть страница вакансии с IČO), а не объявление площадки
     */
    public boolean own() {
        return UUID.matcher(externalId).matches();
    }

    private static String cut(String value) {
        return value == null || value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }
}
