package com.roleorienta.worker.vacancy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки жизненного цикла вакансий ({@code app.vacancy.*}).
 *
 * @param closeAfterMissingReads полных чтений подряд без публикации до её закрытия
 */
@ConfigurationProperties("app.vacancy")
public record VacancyProperties(@DefaultValue("3") int closeAfterMissingReads) {
}
