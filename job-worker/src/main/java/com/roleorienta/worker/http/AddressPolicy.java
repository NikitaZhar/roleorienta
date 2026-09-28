package com.roleorienta.worker.http;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Какие адреса назначения разрешены для внешних запросов (защита от SSRF, технический документ §10).
 *
 * <p>Запрещены: loopback, «любой» адрес, link-local (в том числе облачный metadata 169.254.169.254),
 * частные сети (10/8, 172.16/12, 192.168/16), multicast, 0.0.0.0/8, carrier-grade NAT 100.64/10,
 * IPv6 unique-local fc00::/7; IPv4, записанный как IPv6 ({@code ::ffff:a.b.c.d}), проверяется как
 * IPv4.</p>
 */
public class AddressPolicy {

    private static final int IPV4_MAPPED_PREFIX_BYTES = 10;
    private static final int IPV4_MAPPED_MARKER = 0xff;
    private static final int IPV4_LENGTH = 4;
    private static final int IPV6_LENGTH = 16;
    private static final int BYTE_MASK = 0xff;
    private static final int ULA_MASK = 0xfe;
    private static final int ULA_PREFIX = 0xfc;
    private static final int CGNAT_FIRST = 100;
    private static final int CGNAT_SECOND_MIN = 64;
    private static final int CGNAT_SECOND_MAX = 127;

    private final boolean allowPrivate;

    /**
     * @param allowPrivate {@code true} — разрешить все адреса (только локальные заглушки)
     */
    public AddressPolicy(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /**
     * Проверяет адрес назначения.
     *
     * @param address адрес, в который разрешилось имя хоста
     * @throws BlockedAddressException если адрес запрещён
     */
    public void requireAllowed(InetAddress address) throws BlockedAddressException {
        if (!allowPrivate && isBlocked(unwrapIpv4Mapped(address))) {
            throw new BlockedAddressException("Blocked destination address: " + address.getHostAddress());
        }
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & BYTE_MASK;
        if (address instanceof Inet6Address) {
            return (first & ULA_MASK) == ULA_PREFIX;
        }
        int second = bytes[1] & BYTE_MASK;
        return first == 0 || (first == CGNAT_FIRST && second >= CGNAT_SECOND_MIN && second <= CGNAT_SECOND_MAX);
    }

    private static InetAddress unwrapIpv4Mapped(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != IPV6_LENGTH) {
            return address;
        }
        for (int index = 0; index < IPV4_MAPPED_PREFIX_BYTES; index++) {
            if (bytes[index] != 0) {
                return address;
            }
        }
        if ((bytes[IPV4_MAPPED_PREFIX_BYTES] & BYTE_MASK) != IPV4_MAPPED_MARKER
                || (bytes[IPV4_MAPPED_PREFIX_BYTES + 1] & BYTE_MASK) != IPV4_MAPPED_MARKER) {
            return address;
        }
        byte[] ipv4 = new byte[IPV4_LENGTH];
        System.arraycopy(bytes, IPV6_LENGTH - IPV4_LENGTH, ipv4, 0, IPV4_LENGTH);
        try {
            return InetAddress.getByAddress(ipv4);
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("4-byte address is always valid", impossible);
        }
    }
}
