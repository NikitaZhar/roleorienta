package com.roleorienta.worker.http;

/**
 * Запрос отклонён контуром защиты от SSRF (§9, A13).
 *
 * <p>Бросается, когда адрес назначения не проходит проверку (приватный/loopback/
 * link-local/metadata-диапазон), либо схема URL не входит в allow-list (только
 * {@code http}/{@code https}). Наследует {@link RuntimeException}, чтобы
 * пробрасываться через слои HTTP-клиента (в т.ч. из резолвера имён) без
 * контрактных {@code throws}. Обработчик задания трактует это как неуспех (§6):
 * задание не должно молча «протечь» на внутренний адрес.</p>
 */
public class SsrfBlockedException extends RuntimeException {

    public SsrfBlockedException(String message) {
        super(message);
    }
}
