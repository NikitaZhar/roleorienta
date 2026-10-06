package com.roleorienta.api.vacancy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки видимости накопленного списка ({@code app.delivery.*}); значение то же, что у выдачи в job-worker.
 *
 * @param hideAfter срок без подтверждения (бизнес-описание §4.3): вакансия, не подтверждённая дольше, в списке не
 *                  показывается, но и не закрывается
 */
@ConfigurationProperties("app.delivery")
public record VacancyProperties(@DefaultValue("30d") Duration hideAfter) {
}
