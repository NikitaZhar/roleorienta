package com.roleorienta.worker.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.springframework.stereotype.Component;

/**
 * Единый контур защиты от SSRF (§9, A13) для исходящих запросов сбора/обнаружения.
 *
 * <p>Даёт две проверки, применяемые в {@link SourceHttpClient}:</p>
 * <ol>
 *   <li>{@link #checkScheme(String)} — allow-list схем URL (только http/https),
 *       быстрый отказ до сети;</li>
 *   <li>{@link #dnsResolver()} — резолвер имён для HTTP-клиента, применяющий
 *       {@link AddressPolicy} к <b>каждому</b> резолвнутому адресу. Клиент
 *       подключается только к проверенным здесь адресам и не резолвит имя повторно —
 *       это и есть защита от DNS-rebinding (A13): «проверяемый адрес связан с
 *       фактическим подключением». Резолвер вызывается на каждом переходе, поэтому
 *       редирект на приватный адрес тоже ре-валидируется и блокируется.</li>
 * </ol>
 *
 * <p>Ключевое требование A13: этот путь <b>обязателен</b> — адаптеры и слой
 * обнаружения ходят наружу только через {@link SourceHttpClient}; {@code Jsoup.connect},
 * сторонние SDK или отдельный raw-{@code HttpClient} не должны его обходить.</p>
 */
@Component
public class SsrfGuard {

    private final AddressPolicy policy;

    /** Продакшен-конструктор: строгая политика (приватные адреса запрещены). */
    public SsrfGuard() {
        this(AddressPolicy.strict());
    }

    /**
     * @param policy политика допустимости адресов (в тестах — permissive для
     *               локального сервера)
     */
    public SsrfGuard(AddressPolicy policy) {
        this.policy = policy;
    }

    /**
     * Проверяет схему URL до выхода в сеть.
     *
     * @param url полный адрес запроса
     * @throws SsrfBlockedException если схема не http/https, либо URL без схемы/хоста
     */
    public void checkScheme(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new SsrfBlockedException("Некорректный URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new SsrfBlockedException("URL без схемы: " + url);
        }
        String normalized = scheme.toLowerCase(Locale.ROOT);
        if (!normalized.equals("http") && !normalized.equals("https")) {
            throw new SsrfBlockedException(
                    "Запрещённая схема URL (разрешены только http/https): " + scheme);
        }
        if (uri.getHost() == null) {
            throw new SsrfBlockedException("URL без хоста: " + url);
        }
    }

    /**
     * Резолвер имён с проверкой каждого адреса по {@link AddressPolicy}. Передаётся
     * в connection manager HTTP-клиента, чтобы подключение шло только на разрешённые IP.
     */
    public DnsResolver dnsResolver() {
        return new SystemDefaultDnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] resolved = super.resolve(host);
                for (InetAddress addr : resolved) {
                    policy.requireAllowed(addr);
                }
                return resolved;
            }
        };
    }
}
