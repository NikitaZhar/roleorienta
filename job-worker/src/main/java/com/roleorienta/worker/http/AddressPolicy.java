package com.roleorienta.worker.http;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Политика допустимости IP-адреса назначения для исходящих запросов сбора/обнаружения
 * (§9, A13 — deny-list адресов).
 *
 * <p>Проверка ведётся по <b>фактически резолвнутому</b> адресу, а не по имени хоста:
 * имя может резолвиться в приватный адрес (в т.ч. атака DNS-rebinding). Поэтому эта
 * политика применяется в резолвере имён HTTP-клиента к каждому полученному адресу
 * (см. {@link SsrfGuard}), чтобы подключение шло только на разрешённый IP.</p>
 *
 * <p>Блокируются: loopback (127.0.0.0/8, ::1), «любой» (0.0.0.0, ::), link-local
 * (169.254.0.0/16 — включая cloud-metadata 169.254.169.254 — и fe80::/10),
 * site-local IPv4 (10/8, 172.16/12, 192.168/16), multicast, ULA IPv6 (fc00::/7),
 * carrier-grade NAT / metadata 100.64.0.0/10 (в т.ч. 100.100.100.200), а также
 * IPv4-mapped IPv6, приводимый к встроенному IPv4 и проверяемый повторно.</p>
 *
 * <p>Обратная сторона A13: <b>не</b> запрещать приватные адреса всем клиентам
 * приложения — штатные внутренние интеграции (БД, брокер, объектное хранилище)
 * настраиваются отдельными доверенными путями. Эта политика — только для URL,
 * пришедших из внешнего ввода/источников.</p>
 */
public class AddressPolicy {

    private final boolean allowPrivate;

    /**
     * @param allowPrivate если {@code true}, приватные/loopback-адреса разрешены
     *                     (используется только в тестах для локального сервера);
     *                     в продакшене всегда {@code false}
     */
    public AddressPolicy(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /** Строгая политика по умолчанию: приватные адреса запрещены. */
    public static AddressPolicy strict() {
        return new AddressPolicy(false);
    }

    /**
     * Бросает {@link SsrfBlockedException}, если адрес запрещён политикой.
     *
     * @param addr резолвнутый адрес назначения
     */
    public void requireAllowed(InetAddress addr) {
        if (!allowPrivate && isBlocked(addr)) {
            throw new SsrfBlockedException("Заблокирован адрес назначения (SSRF): " + addr.getHostAddress());
        }
    }

    private boolean isBlocked(InetAddress raw) {
        InetAddress addr = unwrapV4Mapped(raw);
        if (addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] octets = addr.getAddress();
        if (addr instanceof Inet6Address) {
            // ULA fc00::/7 — старшие 7 бит равны 1111 110x
            int first = octets[0] & 0xff;
            return (first & 0xfe) == 0xfc;
        }
        int b0 = octets[0] & 0xff;
        int b1 = octets[1] & 0xff;
        // 0.0.0.0/8 — «этот хост»
        if (b0 == 0) {
            return true;
        }
        // carrier-grade NAT 100.64.0.0/10 (включает metadata-адрес 100.100.100.200)
        return b0 == 100 && b1 >= 64 && b1 <= 127;
    }

    /**
     * Приводит IPv4-mapped IPv6 ({@code ::ffff:a.b.c.d}) к встроенному IPv4, чтобы
     * такой адрес нельзя было использовать в обход IPv4-проверок. Прочие адреса
     * возвращаются как есть.
     */
    private InetAddress unwrapV4Mapped(InetAddress addr) {
        if (addr instanceof Inet6Address) {
            byte[] octets = addr.getAddress();
            boolean prefixZero = true;
            for (int index = 0; index < 10; index++) {
                if (octets[index] != 0) {
                    prefixZero = false;
                    break;
                }
            }
            if (prefixZero && (octets[10] & 0xff) == 0xff && (octets[11] & 0xff) == 0xff) {
                try {
                    return InetAddress.getByAddress(new byte[] {octets[12], octets[13], octets[14], octets[15]});
                } catch (UnknownHostException ignored) {
                    // длина 4 всегда валидна; недостижимо
                }
            }
        }
        return addr;
    }
}
