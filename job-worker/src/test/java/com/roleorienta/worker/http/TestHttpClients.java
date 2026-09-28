package com.roleorienta.worker.http;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Внешний HTTP-клиент для тестов адаптеров на локальных заглушках: внутренние адреса разрешены,
 * бюджет хоста не ограничивает.
 */
public final class TestHttpClients {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BODY_BYTES = 1_000_000;

    private TestHttpClients() {
    }

    /**
     * @return клиент для заглушки на 127.0.0.1
     */
    public static ExternalHttpClient forLocalStub() {
        return new ExternalHttpClient(
                new ExternalHttpProperties(TIMEOUT, TIMEOUT, MAX_REDIRECTS, MAX_BODY_BYTES, true),
                new PolitenessProperties("RoleorientaTest/1.0", Duration.ZERO, Duration.ZERO),
                host -> Optional.of(Duration.ZERO), Clock.systemUTC());
    }
}
