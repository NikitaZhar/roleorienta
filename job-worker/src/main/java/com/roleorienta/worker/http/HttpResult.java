package com.roleorienta.worker.http;

import java.time.Duration;

/**
 * Результат внешнего запроса, уже разобранный по смыслу для ядра (бизнес-описание §4.6):
 * успех, временный отказ (повторить позже) или постоянный отказ (повтор не поможет).
 */
public sealed interface HttpResult {

    /**
     * Успешный ответ (2xx).
     *
     * @param status код ответа
     * @param body   тело ответа
     */
    record Success(int status, String body) implements HttpResult {
    }

    /**
     * Временный отказ: таймаут, обрыв, 5xx, 429.
     *
     * @param reason     причина для журнала
     * @param retryAfter срок, раньше которого источник просит не повторять ({@code Retry-After});
     *                   {@link Duration#ZERO} — не указан
     */
    record TemporaryFailure(String reason, Duration retryAfter) implements HttpResult {
    }

    /**
     * Постоянный отказ.
     *
     * @param kind   вид отказа
     * @param reason причина для журнала
     */
    record PermanentFailure(Kind kind, String reason) implements HttpResult {
    }

    /**
     * Вид постоянного отказа.
     */
    enum Kind {
        /** 401, 403 — доступ запрещён; ограничения не обходятся. */
        ACCESS_DENIED,
        /** 404, 410 — страница удалена. */
        NOT_FOUND,
        /** Запрещённая схема или внутренний адрес (защита от SSRF). */
        BLOCKED,
        /** Тело ответа больше потолка. */
        TOO_LARGE,
        /** Прочие ошибки запроса: иные 4xx, лишние или циклические редиректы. */
        CLIENT_ERROR,
        /** Путь запрещён robots.txt; запрос не отправлялся. */
        USE_FORBIDDEN
    }
}
