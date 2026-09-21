package com.roleorienta.worker.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Allow-list схем URL в {@link SsrfGuard#checkScheme(String)} (§9, A13). */
class SsrfGuardSchemeTest {

    private final SsrfGuard guard = new SsrfGuard();

    @Test
    void rejectsNonHttpSchemes() {
        for (String url : new String[] {
                "file:///etc/passwd",
                "ftp://example.com/x",
                "gopher://example.com/",
                "data:text/plain;base64,aGk=",
                "jar:file:/x!/y"
        }) {
            assertThrows(SsrfBlockedException.class, () -> guard.checkScheme(url),
                    "должна отклоняться схема: " + url);
        }
    }

    @Test
    void rejectsMissingSchemeOrHost() {
        assertThrows(SsrfBlockedException.class, () -> guard.checkScheme("/relative/path"));
        assertThrows(SsrfBlockedException.class, () -> guard.checkScheme("http:///nohost"));
    }

    @Test
    void acceptsHttpAndHttps() {
        assertDoesNotThrow(() -> guard.checkScheme("http://boards.greenhouse.io/v1/boards/acme/jobs"));
        assertDoesNotThrow(() -> guard.checkScheme("https://example.com/feed"));
        assertDoesNotThrow(() -> guard.checkScheme("HTTPS://Example.com/Feed"));
    }
}
