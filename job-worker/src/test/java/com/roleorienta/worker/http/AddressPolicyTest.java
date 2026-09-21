package com.roleorienta.worker.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/**
 * Классификация адресов строгой политикой (§9, A13). Литеральные IP не резолвятся
 * по сети — {@link InetAddress#getByName(String)} на них не делает DNS-запрос.
 */
class AddressPolicyTest {

    private final AddressPolicy strict = AddressPolicy.strict();

    private static final String[] BLOCKED = {
        "127.0.0.1",            // loopback
        "0.0.0.0",             // any-local
        "10.1.2.3",            // private A
        "172.16.5.5",          // private B
        "192.168.1.1",         // private C
        "169.254.169.254",     // link-local + cloud metadata
        "100.64.1.1",          // carrier-grade NAT
        "100.100.100.200",     // metadata (внутри CGN)
        "::1",                 // IPv6 loopback
        "fe80::1",             // IPv6 link-local
        "fc00::1",             // IPv6 ULA
        "fd12:3456::1",        // IPv6 ULA
        "::ffff:10.0.0.1",     // IPv4-mapped приватный
        "::ffff:127.0.0.1"     // IPv4-mapped loopback
    };

    private static final String[] ALLOWED = {
        "8.8.8.8",
        "1.1.1.1",
        "93.184.216.34",
        "2001:4860:4860::8888"
    };

    @Test
    void blocksPrivateAndSpecialAddresses() throws UnknownHostException {
        for (String literal : BLOCKED) {
            InetAddress addr = InetAddress.getByName(literal);
            assertThrows(SsrfBlockedException.class, () -> strict.requireAllowed(addr),
                    "должен блокироваться: " + literal);
        }
    }

    @Test
    void allowsPublicAddresses() throws UnknownHostException {
        for (String literal : ALLOWED) {
            InetAddress addr = InetAddress.getByName(literal);
            assertDoesNotThrow(() -> strict.requireAllowed(addr), "должен проходить: " + literal);
        }
    }

    @Test
    void permissivePolicyAllowsLoopback() throws UnknownHostException {
        AddressPolicy permissive = new AddressPolicy(true);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertDoesNotThrow(() -> permissive.requireAllowed(loopback));
    }
}
