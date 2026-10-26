package com.roleorienta.api.company;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Компании и ввод адресов кадровых страниц ({@code app.company-requests.*}; §137, бизнес-описание §5).
 *
 * @param dailyLimit адресов от одного пользователя за последние сутки не больше (решение владельца 2026-10-19 — 20)
 */
@ConfigurationProperties("app.company-requests")
public record CompanyProperties(@DefaultValue("20") int dailyLimit) {
}
