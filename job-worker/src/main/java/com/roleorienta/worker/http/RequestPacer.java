package com.roleorienta.worker.http;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Темп запросов к источникам — вежливость (§57, B1-минимум; ADR-9). Одна реплика
 * воркера не отправляет к одному провайдеру запросы чаще заданного интервала и
 * соблюдает {@code Retry-After}.
 *
 * <p><b>Ключ темпа</b> — зарегистрированный домен хоста (две последние метки):
 * все тенанты {@code *.wdN.myworkdayjobs.com} живут на общей инфраструктуре Workday,
 * поэтому интервал считается для {@code myworkdayjobs.com} целиком, а не на хост
 * тенанта; так же {@code index.commoncrawl.org} → {@code commoncrawl.org}. Для пилота
 * этого приближения достаточно (домены вида {@code co.uk} в охвате нет).</p>
 *
 * <p><b>Как.</b> Для ключа хранится момент следующего разрешённого запроса. {@link #acquire}
 * атомарно резервирует слот {@code max(сейчас, следующий)} и сдвигает следующий на интервал;
 * если до слота ждать не дольше {@code max-wait-ms}, поток спит, иначе — слот не резервируется
 * и бросается {@link SourceBackoffException} (задание повторит брокер/тик, поток не
 * блокируется на минуты). {@link #backoff} по {@code Retry-After} отодвигает следующий
 * слот не раньше указанного момента (потолок — {@code max-backoff-ms}).</p>
 *
 * <p><b>Границы.</b> Темп — на процесс: при нескольких репликах суммарная частота
 * умножается на их число (общий лимит через БД/Redis — не в этом срезе). Бюджет
 * «сколько анализировать в день» — отдельный лимит, не здесь.</p>
 */
@Component
public class RequestPacer {

    private static final Logger log = LoggerFactory.getLogger(RequestPacer.class);

    private final long defaultIntervalMs;
    private final Map<String, Long> intervalsMs;
    private final long maxWaitMs;
    private final long maxBackoffMs;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, AtomicLong> nextSlot = new ConcurrentHashMap<>();

    /** Ожидание — отдельно, чтобы тест не спал по-настоящему. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * @param properties настройки темпа ({@code app.source.pacing})
     */
    @Autowired
    public RequestPacer(SourcePacingProperties properties) {
        this(properties.defaultIntervalMs(), properties.intervalsMs(), properties.maxWaitMs(),
                properties.maxBackoffMs(), Clock.systemUTC(), Thread::sleep);
    }

    RequestPacer(long defaultIntervalMs, Map<String, Long> intervalsMs, long maxWaitMs,
                 long maxBackoffMs, Clock clock, Sleeper sleeper) {
        this.defaultIntervalMs = defaultIntervalMs;
        this.intervalsMs = Map.copyOf(intervalsMs);
        this.maxWaitMs = maxWaitMs;
        this.maxBackoffMs = maxBackoffMs;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Без темпа — для тестов, где клиент ходит на локальные заглушки.
     *
     * @return пейсер с нулевым интервалом
     */
    public static RequestPacer unpaced() {
        return new RequestPacer(0, Map.of(), Long.MAX_VALUE, 0, Clock.systemUTC(), millis -> { });
    }

    /**
     * Ключ темпа для адреса: зарегистрированный домен хоста (две последние метки),
     * IP-адрес или однометочный хост — как есть.
     *
     * @param url адрес запроса
     * @return ключ темпа
     */
    public static String keyOf(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
        if (host == null) {
            return "invalid";
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.matches("[0-9.]+") || host.contains(":")) {
            return host;
        }
        String[] labels = host.split("\\.");
        return labels.length <= 2 ? host : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    /**
     * Дождаться разрешённого момента для запроса по ключу.
     *
     * @param key ключ темпа
     * @throws SourceBackoffException если ждать пришлось бы дольше {@code max-wait-ms}
     */
    public void acquire(String key) {
        long interval = intervalsMs.getOrDefault(key, defaultIntervalMs);
        AtomicLong next = nextSlot.computeIfAbsent(key, k -> new AtomicLong(0));
        long now = clock.millis();
        long slot;
        while (true) {
            long current = next.get();
            slot = Math.max(now, current);
            if (slot - now > maxWaitMs) {
                throw new SourceBackoffException(key, Duration.ofMillis(slot - now));
            }
            if (next.compareAndSet(current, slot + interval)) {
                break;
            }
        }
        long wait = slot - now;
        if (wait > 0) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceBackoffException(key, Duration.ofMillis(wait));
            }
        }
    }

    /**
     * Источник попросил подождать ({@code Retry-After}): следующий запрос по ключу —
     * не раньше, чем через {@code delay} (с потолком {@code max-backoff-ms}).
     *
     * @param key   ключ темпа
     * @param delay пауза
     */
    public void backoff(String key, Duration delay) {
        long capped = Math.min(Math.max(0, delay.toMillis()), maxBackoffMs);
        long until = clock.millis() + capped;
        nextSlot.computeIfAbsent(key, k -> new AtomicLong(0)).accumulateAndGet(until, Math::max);
        log.warn("Источник {} попросил паузу: следующий запрос не раньше чем через {} с", key, capped / 1000);
    }
}
