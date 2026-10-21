package com.roleorienta.worker.adapter.stateportal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки государственного портала вакансий ({@code app.state-portal.*}).
 *
 * @param baseUrl        адрес портала; в тестах — заглушка
 * @param offersPageSize вакансий работодателя на страницу его списка (поиск сайта по первой своей вакансии)
 * @param task           размер задания поиска сайта
 * @param recheckAfter   срок до повторного поиска сайта работодателя и его страницы компании на profesia.sk
 * @param datesPerRecord дат размещения со страниц объявлений за задание записи источника из общего списка
 *                       ({@code PORTAL_RECORD}, §168): даты нет в общем списке, за каждой — запрос
 */
@ConfigurationProperties("app.state-portal")
public record StatePortalProperties(
        @DefaultValue("https://www.sluzbyzamestnanosti.gov.sk") String baseUrl,
        @DefaultValue("50") int offersPageSize,
        @DefaultValue Task task,
        @DefaultValue("7d") Duration recheckAfter,
        @DefaultValue("10") int datesPerRecord) {

    /**
     * Размер задания шага «сайт»: работодателей не больше {@code employersPerTask} и не дольше
     * {@code timeBudget} — портал делят с чтением его общего списка, и время запроса зависит от очереди к хосту, поэтому
     * число работодателей само по себе срок задания не задаёт (стенограмма §94, §95).
     *
     * @param employersPerTask работодателей за задание
     * @param timeBudget    после этого срока новые работодатели не начинаются — следующее задание цепочки
     */
    public record Task(
            @DefaultValue("20") int employersPerTask,
            @DefaultValue("5m") Duration timeBudget) {
    }
}
