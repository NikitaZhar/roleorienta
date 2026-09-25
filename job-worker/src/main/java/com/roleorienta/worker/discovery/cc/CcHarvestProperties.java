package com.roleorienta.worker.discovery.cc;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Конфигурация входа обнаружения по индексу Common Crawl (§55, A1b).
 *
 * <p>Два бюджета (A29) разделены намеренно: {@code pagesPerPass} ограничивает нагрузку на
 * общий CDX-сервер (при {@code pageSize=1} страница ≈ 3 тыс. URL, коллекция по Workday —
 * ~24 страницы, §54/§55.5a),
 * {@code maxFanOut} — число заданий {@code DISCOVER_EMPLOYER} за проход (каждое — запрос к
 * ленте тенанта). Доски сверх бюджета ждут в накопителе {@code harvested_board}.</p>
 *
 * @param inputs       включённые входы (шаблон адресов и система найма, {@link CcInput}); у
 *                     каждого свой курсор, бюджет страниц — на каждый вход (§91)
 * @param pageSize     размер страницы CDX в блоках (~3000 URL на блок); мелкая страница
 *                     укладывается в тайм-аут шлюза индекса (§55.5a)
 * @param pagesPerPass максимум страниц индекса за проход
 * @param maxFanOut    максимум заданий {@code DISCOVER_EMPLOYER} за проход
 * @param leaseSeconds длительность аренды прохода (защита от параллельного обхода), с
 */
@ConfigurationProperties(prefix = "app.discovery.cc")
public record CcHarvestProperties(
        @DefaultValue("WORKDAY") List<CcInput> inputs,
        @DefaultValue("1") int pageSize,
        @DefaultValue("1") int pagesPerPass,
        @DefaultValue("20") int maxFanOut,
        @DefaultValue("900") long leaseSeconds) {
}
