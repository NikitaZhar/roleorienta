package com.roleorienta.worker.adapter.stateportal;

/**
 * Сайт работодателя по детали его вакансии на государственном портале.
 *
 * @param host        хост сайта; {@code null} — сайт не указан (нет своей вакансии портала, нет
 *                    «Internetová adresa» и почта на общем почтовом сервисе, IČO детали не совпало)
 * @param evidenceUrl деталь вакансии, на которой найден сайт; {@code null}, если сайта нет
 */
public record PortalSite(String host, String evidenceUrl) {
}
