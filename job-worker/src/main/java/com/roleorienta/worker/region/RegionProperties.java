package com.roleorienta.worker.region;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Путь «от региона и профиля к карьерной странице» (план R1, R7; §95, ADR-19): импорт компаний из
 * реестра юрлиц и проверка их сайтов. Первая страна — Словакия (реестр RPO).
 *
 * @param enabled     включён ли фоновый проход ({@link RegionTrigger}); в тестах выключен
 * @param rpo         поиск в реестре RPO
 * @param importLimit сколько компаний держать в выборке: импорт останавливается на этом числе
 * @param checkBatch  компаний на проверку сайта за проход
 * @param lookupsPerPass карточек реестра за проход (вид деятельности есть только в карточке —
 *                    отдельный запрос на каждую найденную компанию)
 */
@ConfigurationProperties(prefix = "app.region")
public record RegionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue Rpo rpo,
        @DefaultValue("50") int importLimit,
        @DefaultValue("5") int checkBatch,
        @DefaultValue("60") int lookupsPerPass) {

    /**
     * Поиск IT-компаний в реестре юрлиц Словакии RPO (открытый API, CC BY 4.0).
     *
     * @param baseUrl        адрес API
     * @param activityTerms  слова поиска по основному виду деятельности (поиск полнотекстовый)
     * @param codePrefixes   начало кода вида деятельности (SK NACE), которое считается профилем:
     *                       62 — программирование и IT-услуги
     * @param municipalities города поиска: у API обязателен фильтр и не больше 500 записей на запрос
     */
    public record Rpo(
            @DefaultValue("https://api.statistics.sk/rpo/v1") String baseUrl,
            @DefaultValue({"programovanie", "informačných technológií"}) List<String> activityTerms,
            @DefaultValue("62") List<String> codePrefixes,
            @DefaultValue({"Bratislava", "Košice", "Žilina", "Banská Bystrica", "Nitra", "Prešov", "Trnava",
                    "Trenčín"}) List<String> municipalities) {
    }
}
