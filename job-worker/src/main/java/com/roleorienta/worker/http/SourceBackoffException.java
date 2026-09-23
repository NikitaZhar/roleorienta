package com.roleorienta.worker.http;

import java.time.Duration;

/**
 * Запрос к источнику не выполнен, потому что для него действует пауза вежливости
 * (§57, B1): интервал между запросами к провайдеру или {@code Retry-After} от него
 * требуют ждать дольше, чем допустимо держать поток. Это <b>временный</b> отказ:
 * задание должно быть повторено позже, а не записано как «лента недоступна».
 */
public class SourceBackoffException extends RuntimeException {

    private final String pacingKey;
    private final Duration wait;

    /**
     * @param pacingKey ключ темпа (провайдер/домен), для которого действует пауза
     * @param wait      сколько ещё ждать до разрешённого запроса
     */
    public SourceBackoffException(String pacingKey, Duration wait) {
        super("Пауза вежливости для " + pacingKey + ": ещё " + wait.toSeconds() + " с");
        this.pacingKey = pacingKey;
        this.wait = wait;
    }

    public String getPacingKey() {
        return pacingKey;
    }

    public Duration getWait() {
        return wait;
    }
}
