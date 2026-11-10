package com.roleorienta.worker.vacancy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки жизненного цикла вакансий ({@code app.vacancy.*}).
 *
 * @param closeAfterMissingReads      полных чтений подряд без публикации до её закрытия
 * @param closeAfterUnrecognizedReads чтений подряд, в которых список не распознан и не найдено ни одной публикации
 *                                    ({@code LIST_NOT_RECOGNIZED}, своя кадровая страница), до закрытия публикаций
 *                                    источника; считаются вместе с полными чтениями без публикации (§287)
 */
@ConfigurationProperties("app.vacancy")
public record VacancyProperties(@DefaultValue("3") int closeAfterMissingReads,
        @DefaultValue("5") int closeAfterUnrecognizedReads) {
}
