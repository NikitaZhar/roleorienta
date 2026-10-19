package com.roleorienta.api.company;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Компании и ввод адресов кадровых страниц ({@code app.company-requests.*}; §137, бизнес-описание §5).
 *
 * @param dailyLimit         адресов от одного пользователя за последние сутки не больше (решение владельца
 *                           2026-10-19 — 20)
 * @param hiringMinEmployees признак найма у компании без сайта: сотрудников по RÚZ не меньше; то же значение у
 *                           job-worker ({@code app.site-name.min-employees}) — общая переменная окружения
 *                           {@code HIRING_MIN_EMPLOYEES} (аудит §143)
 */
@ConfigurationProperties("app.company-requests")
public record CompanyProperties(@DefaultValue("20") int dailyLimit, @DefaultValue("10") int hiringMinEmployees) {
}
