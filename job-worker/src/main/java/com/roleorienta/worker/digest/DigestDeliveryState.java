package com.roleorienta.worker.digest;

/** Состояние доставки письма-дайджеста (A7, §88; {@code digest_delivery.state}). */
public enum DigestDeliveryState {
    /** Ждёт отправки или повторной попытки. */
    PENDING,
    /** Отправлено: SMTP-сервер принял письмо. */
    SENT,
    /** Попытки исчерпаны; окно больше не отправляется. */
    FAILED
}
