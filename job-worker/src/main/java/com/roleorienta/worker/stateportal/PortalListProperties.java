package com.roleorienta.worker.stateportal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки чтения общего списка вакансий портала ({@code app.state-portal.list.*}; стенограмма §105).
 *
 * @param pageSize         записей на страницу списка (портал отдаёт 200)
 * @param pagesPerTask     страниц за задание полного обхода — квота в час: продолжение обхода — в следующем часу
 * @param cycleEvery       новый полный обход начинается не раньше, чем через этот срок после начала прошлого
 * @param timeBudget       после этого срока задание не начинает новую страницу — продолжение в следующем часу
 * @param nameRecheckAfter название, по которому поиск работодателей портала не дал IČO, ищется снова через этот срок
 */
@ConfigurationProperties("app.state-portal.list")
public record PortalListProperties(
        @DefaultValue("200") int pageSize,
        @DefaultValue("10") int pagesPerTask,
        @DefaultValue("20h") Duration cycleEvery,
        @DefaultValue("5m") Duration timeBudget,
        @DefaultValue("7d") Duration nameRecheckAfter) {
}
