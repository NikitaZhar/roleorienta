package com.roleorienta.worker.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestPacer} (§57) на управляемых часах: ключ темпа по домену, интервал между
 * запросами одного домена, независимость доменов, отказ вместо долгого ожидания,
 * пауза по {@code Retry-After} и её потолок. Ожидание не настоящее — «сон» сдвигает часы.
 */
class RequestPacerTest {

    /** Часы, которые сдвигает только тест (и «сон» пейсера). */
    private static final class ManualClock extends Clock {
        private long millis = 1_000_000;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    private final ManualClock clock = new ManualClock();
    private final List<Long> sleeps = new ArrayList<>();

    private RequestPacer pacer(long defaultInterval, Map<String, Long> intervals, long maxWait, long maxBackoff) {
        SourcePacingProperties properties = new SourcePacingProperties(defaultInterval, intervals, maxWait, maxBackoff, 0);
        return new RequestPacer(properties, clock, ms -> {
            sleeps.add(ms);
            clock.millis += ms;
        });
    }

    @Test
    void keyIsRegisteredDomain() {
        assertEquals("myworkdayjobs.com", RequestPacer.keyOf("https://amgen.wd1.myworkdayjobs.com/wday/cxs/x/y/jobs"));
        assertEquals("myworkdayjobs.com", RequestPacer.keyOf("https://3m.wd5.myworkdayjobs.com/"));
        assertEquals("commoncrawl.org", RequestPacer.keyOf("https://index.commoncrawl.org/collinfo.json"));
        assertEquals("127.0.0.1", RequestPacer.keyOf("http://127.0.0.1:8080/x"));
        assertEquals("source-stub", RequestPacer.keyOf("http://source-stub:8080/x"));
    }

    @Test
    void sameDomainIsSpacedByInterval() {
        RequestPacer pacer = pacer(1000, Map.of("myworkdayjobs.com", 2000L), 10_000, 60_000);

        pacer.acquire("myworkdayjobs.com");
        pacer.acquire("myworkdayjobs.com");
        pacer.acquire("myworkdayjobs.com");

        assertEquals(List.of(2000L, 2000L), sleeps, "первый сразу, дальше — через интервал домена");
    }

    @Test
    void domainsAreIndependent() {
        RequestPacer pacer = pacer(1000, Map.of(), 10_000, 60_000);

        pacer.acquire("myworkdayjobs.com");
        pacer.acquire("commoncrawl.org");

        assertTrue(sleeps.isEmpty());
    }

    @Test
    void tooLongWaitThrowsInsteadOfBlocking() {
        RequestPacer pacer = pacer(5000, Map.of(), 3000, 60_000);
        pacer.acquire("x.com");

        SourceBackoffException backoff = assertThrows(SourceBackoffException.class, () -> pacer.acquire("x.com"));

        assertEquals("x.com", backoff.getPacingKey());
        assertTrue(sleeps.isEmpty(), "поток не спал");
        clock.millis += 5000;
        pacer.acquire("x.com"); // отказ не занял слот — после интервала запрос проходит сразу
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void retryAfterPostponesDomainWithCap() {
        RequestPacer pacer = pacer(0, Map.of(), 10_000, 30_000);

        pacer.backoff("myworkdayjobs.com", Duration.ofSeconds(8));
        pacer.acquire("myworkdayjobs.com");
        assertEquals(List.of(8000L), sleeps);

        pacer.backoff("myworkdayjobs.com", Duration.ofHours(2)); // потолок 30 с > max-wait 10 с
        assertThrows(SourceBackoffException.class, () -> pacer.acquire("myworkdayjobs.com"));
        clock.millis += 30_000;
        pacer.acquire("myworkdayjobs.com");
    }
}
