package com.roleorienta.worker.digest;

import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки сводки новых вакансий на почту ({@code app.digest.*}; стенограмма §164).
 *
 * @param from    адрес отправителя (у Brevo — подтверждённый в нём); пусто — письма не отправляются
 * @param appUrl  адрес приложения — ссылка в письме
 * @param sendAt  время письма по часовому поясу пользователя (бизнес-описание §6 — 8:00)
 * @param maxRows строк вакансий в письме не больше; остальные — «и ещё N»
 */
@ConfigurationProperties("app.digest")
public record DigestProperties(@DefaultValue("") String from, @DefaultValue("http://localhost:5173") String appUrl,
        @DefaultValue("08:00") LocalTime sendAt, @DefaultValue("20") int maxRows) {
}
