package com.roleorienta.worker.http;

import java.net.UnknownHostException;

/**
 * Хост разрешился во внутренний адрес — соединение запрещено (защита от SSRF).
 *
 * <p>Наследует {@link UnknownHostException}: его бросает определение адреса, и HTTP-клиент
 * передаёт его наружу как ошибку ввода-вывода, не пытаясь соединиться.</p>
 */
public class BlockedAddressException extends UnknownHostException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message описание заблокированного адреса
     */
    public BlockedAddressException(String message) {
        super(message);
    }
}
