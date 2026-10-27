package com.roleorienta.api.web;

import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Язык интерфейса текущего запроса (§204): Spring MVC кладёт язык заголовка {@code Accept-Language} в
 * {@code LocaleContextHolder} на время обработки запроса — параметр {@code Locale} в каждом методе контроллера не
 * нужен (https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/localeresolver.html).
 */
public final class RequestLanguage {

    private RequestLanguage() {
    }

    /**
     * @return язык названий: {@code ru} или {@code en} (языки интерфейса)
     */
    public static String current() {
        return "ru".equals(LocaleContextHolder.getLocale().getLanguage()) ? "ru" : "en";
    }
}
