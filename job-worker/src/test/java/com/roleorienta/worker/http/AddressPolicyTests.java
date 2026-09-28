package com.roleorienta.worker.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Политика адресов: внутренние запрещены, публичные разрешены.
 */
class AddressPolicyTests {

    private final AddressPolicy strict = new AddressPolicy(false);

    /**
     * Внутренние адреса IPv4 и IPv6, включая IPv4, записанный как IPv6.
     */
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
            "0.0.0.0", "100.64.0.1", "224.0.0.1", "::1", "fe80::1", "fc00::1", "::ffff:10.0.0.1"})
    void blocksInternalAddresses(String literal) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(literal);

        assertThatThrownBy(() -> strict.requireAllowed(address)).isInstanceOf(BlockedAddressException.class);
    }

    /**
     * Публичные адреса разрешены.
     */
    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "2606:4700::1111", "100.128.0.1"})
    void allowsPublicAddresses(String literal) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(literal);

        assertThatCode(() -> strict.requireAllowed(address)).doesNotThrowAnyException();
    }
}
