package com.roleorienta.worker.adapter.stateportal;

/**
 * Сайт работодателя по детали его вакансии на государственном портале (алгоритм поиска сайта версии 3, шаг 2;
 * технический документ §5.1).
 *
 * @param website     «Internetová adresa» полным адресом (со схемой; путь сохраняется); {@code null} — поля нет
 *                    или в нём не адрес
 * @param mailHost    домен почты контакта — только если {@code website} нет, домен не общий почтовый сервис и
 *                    в нём слово или инициалы названия; {@code null} — иначе
 * @param evidenceUrl деталь вакансии, на которой найден адрес; {@code null}, если адреса нет
 */
public record PortalSite(String website, String mailHost, String evidenceUrl) {

    /** Сайта нет: нет своей вакансии портала, IČO детали не совпало или адреса нет. */
    public static final PortalSite NONE = new PortalSite(null, null, null);
}
