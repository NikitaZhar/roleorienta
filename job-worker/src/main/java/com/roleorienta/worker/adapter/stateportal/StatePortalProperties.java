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
 * @param task           размер задания проверки и поиска сайта
 * @param recheckAfter   срок до повторной проверки работодателя без источника
 */
@ConfigurationProperties("app.state-portal")
public record StatePortalProperties(
        @DefaultValue("https://www.sluzbyzamestnanosti.gov.sk") String baseUrl,
        @DefaultValue("50") int offersPageSize,
        @DefaultValue("20") int maxOfferPages,
        @DefaultValue Task task,
        @DefaultValue("7d") Duration recheckAfter) {

    /**
     * Размер задания шагов «проверка» и «сайт»: работодателей не больше {@code checksPerTask} и не дольше
     * {@code timeBudget} — портал делят с чтением его вакансий, и время запроса зависит от очереди к хосту, поэтому
     * число работодателей само по себе срок задания не задаёт (стенограмма §94, §95).
     *
     * @param checksPerTask работодателей за задание
     * @param timeBudget    после этого срока новые работодатели не начинаются — следующее задание цепочки
     */
    public record Task(
            @DefaultValue("150") int checksPerTask,
            @DefaultValue("5m") Duration timeBudget) {
    }
}
