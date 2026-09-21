package com.roleorienta.api.subscription;

import java.time.Instant;

/** Ответы эндпоинтов подписок на компании (§7). */
public final class CompanySubscriptionDtos {

    private CompanySubscriptionDtos() {
    }

    /**
     * Представление подписки.
     *
     * @param companyId   id компании
     * @param companyName имя компании (для списка)
     * @param createdAt   когда подписка создана
     */
    public record SubscriptionResponse(Long companyId, String companyName, Instant createdAt) {

        static SubscriptionResponse of(CompanySubscription subscription) {
            return new SubscriptionResponse(
                    subscription.getCompany().getId(),
                    subscription.getCompany().getName(),
                    subscription.getCreatedAt());
        }
    }
}
