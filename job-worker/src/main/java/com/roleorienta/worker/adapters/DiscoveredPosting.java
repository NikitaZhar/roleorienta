package com.roleorienta.worker.adapters;

/**
 * Публикация, обнаруженная адаптером в ленте источника, в общем виде.
 *
 * <p>Минимальный набор полей на этапе обнаружения страницы ({@code DISCOVER_PAGE}):
 * идентификатор публикации в терминах источника, ссылка и заголовок в исходном
 * виде. Нормализация и извлечение структурированных требований — следующие срезы
 * (§6 техдока). Общий вид не зависит от конкретной системы найма — это и есть
 * смысл единого контракта адаптера (§5).</p>
 *
 * <p>{@code rawLocation} — локация из строки списка, если источник её отдаёт (Workday —
 * {@code locationsText}: «College Station, TX» или «2 Locations»), иначе {@code null}. Нужна
 * гейту рынка у тенантов без фасетов стран и локаций (§73).</p>
 *
 * @param externalId  идентификатор публикации в терминах источника (уникален в паре с источником)
 * @param url         ссылка на публикацию
 * @param rawTitle    заголовок публикации в исходном виде
 * @param rawLocation локация из списка или {@code null}
 */
public record DiscoveredPosting(String externalId, String url, String rawTitle, String rawLocation) {

    /**
     * Публикация без локации в списке (напр. Greenhouse).
     *
     * @param externalId идентификатор публикации в терминах источника
     * @param url        ссылка на публикацию
     * @param rawTitle   заголовок публикации в исходном виде
     */
    public DiscoveredPosting(String externalId, String url, String rawTitle) {
        this(externalId, url, rawTitle, null);
    }
}
