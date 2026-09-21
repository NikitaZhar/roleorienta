package com.roleorienta.worker.http;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Сетевое поведение {@link SourceHttpClient} под строгой политикой (§9, A13):
 * запрос на приватный/metadata-адрес и не-http схему отклоняется. Соединение не
 * устанавливается — адрес отбраковывается в резолвере до подключения, поэтому тест
 * не поднимает сервер и не ходит в сеть.
 */
class SourceHttpClientSsrfTest {

    private final SourceHttpClient client = new SourceHttpClient(new SsrfGuard(), 500, 500, 5);

    @Test
    void blocksLoopback() {
        assertThrows(SsrfBlockedException.class,
                () -> client.getBody("http://127.0.0.1:9/anything"));
    }

    @Test
    void blocksCloudMetadata() {
        assertThrows(SsrfBlockedException.class,
                () -> client.getBody("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void blocksPrivateAddress() {
        assertThrows(SsrfBlockedException.class,
                () -> client.getBody("http://10.0.0.5/internal"));
    }

    @Test
    void blocksNonHttpScheme() {
        assertThrows(SsrfBlockedException.class,
                () -> client.getBody("ftp://example.com/x"));
    }
}
