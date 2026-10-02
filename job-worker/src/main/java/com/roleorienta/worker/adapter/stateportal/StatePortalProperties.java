package com.roleorienta.worker.adapter.stateportal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки государственного портала вакансий ({@code app.state-portal.*}).
 *
 * @param baseUrl        адрес портала; в тестах — заглушка
 * @param offersPageSize вакансий работодателя на страницу списка
 * @param maxOfferPages  потолок страниц списка вакансий работодателя за чтение; упор — неполное чтение
 * @param checksPerTask  работодателей, проверяемых одним заданием (≈ 1 с на запрос — задание укладывается
 *                       в аренду)
 * @param recheckAfter   срок до повторной проверки работодателя без источника
 */
@ConfigurationProperties("app.state-portal")
public record StatePortalProperties(
        @DefaultValue("https://www.sluzbyzamestnanosti.gov.sk") String baseUrl,
        @DefaultValue("50") int offersPageSize,
        @DefaultValue("20") int maxOfferPages,
        @DefaultValue("150") int checksPerTask,
        @DefaultValue("7d") Duration recheckAfter) {
}
