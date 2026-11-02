package com.roleorienta.worker.career;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Повтор неудачной проверки сайта ({@code app.career.retry.*}, §236; решение владельца 2026-11-02).
 *
 * @param unreachableAfter сайт с итогом «не открывается» проверяется снова через столько (остальные — через
 *                         {@link CareerProperties#recheckAfter()}): временный сбой не оставляет компанию без вакансий
 *                         её страницы на весь срок перепроверки
 */
@ConfigurationProperties("app.career.retry")
public record CareerRetryProperties(@DefaultValue("2d") Duration unreachableAfter) {
}
