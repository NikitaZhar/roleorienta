package com.roleorienta.worker.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки внешних HTTP-запросов ({@code app.http.*} в {@code application.yml}).
 *
 * @param connectTimeout        предел установления соединения
 * @param readTimeout           предел ожидания ответа и данных
 * @param maxRedirects          максимум переходов по редиректам
 * @param maxBodyBytes          потолок тела ответа; больше — отказ без чтения остатка
 * @param allowPrivateAddresses разрешить внутренние адреса — только для локальных заглушек
 */
@ConfigurationProperties("app.http")
public record ExternalHttpProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout,
        @DefaultValue("5") int maxRedirects,
        @DefaultValue("5242880") int maxBodyBytes,
        @DefaultValue("false") boolean allowPrivateAddresses) {
}
