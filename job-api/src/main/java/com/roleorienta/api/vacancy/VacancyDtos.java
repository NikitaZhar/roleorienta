package com.roleorienta.api.vacancy;

import java.time.Instant;
import java.util.List;

/**
 * Ответы API накопленного списка, сведений и отметок (технический документ §8; бизнес-описание §6). Отсутствующие
 * сведения — {@code null} («не указано»); полный текст вакансии не возвращается.
 */
public final class VacancyDtos {

    private VacancyDtos() {
    }

    /**
     * Вакансия в списке.
     *
     * @param id       вакансия
     * @param position позиция (название публикации)
     * @param parties  работодатель и агентство
     * @param work     страна и формат работы
     * @param listedAt когда вакансия выдана в список (в списке отмеченных — когда отмечена)
     */
    public record VacancyItem(long id, String position, Parties parties, Work work, Instant listedAt) {
    }

    /**
     * Страница списка.
     *
     * @param items      вакансии, новые сверху
     * @param nextCursor курсор следующей страницы; {@code null} — страниц больше нет
     */
    public record VacancyPage(List<VacancyItem> items, String nextCursor) {
    }

    /**
     * Сведения о вакансии (бизнес-описание §6).
     *
     * @param id          вакансия
     * @param position    позиция
     * @param parties     работодатель и агентство
     * @param work        страна, формат, территория удалённой работы, неопределённость
     * @param publication ссылка, даты, состояние, отметка пользователя
     */
    public record VacancyDetails(long id, String position, Parties parties, Work work, Publication publication) {
    }

    /**
     * Стороны публикации; кадровое агентство не подставляется в поле работодателя.
     *
     * @param employer работодатель; {@code null} — не указан
     * @param agency   кадровое агентство, размещающее публикацию; {@code null} — нет
     */
    public record Parties(String employer, String agency) {
    }

    /**
     * Где и как выполняется работа.
     *
     * @param countries        страны выполнения работы (ISO 3166-1 alpha-2; {@code *} — без ограничения)
     * @param format           {@code OFFICE}, {@code HYBRID}, {@code REMOTE}; {@code null} — не указан или противоречив
     * @param remoteTerritory  территория удалённой работы — страны вакансии с форматом {@code REMOTE}; иначе {@code null}
     * @param countryUncertain страна не ясна (место без страны, «удалённо» без территории) — вакансия включена по
     *                         правилу неопределённости (бизнес-описание §4.4)
     * @param formatUncertain  формат не указан или противоречив, а в условиях пользователя он задан
     */
    public record Work(List<String> countries, String format, List<String> remoteTerritory, boolean countryUncertain,
            boolean formatUncertain) {
    }

    /**
     * Публикация и её проверка.
     *
     * @param url             первичная публикация
     * @param firstSeenAt     обнаружена
     * @param lastConfirmedAt последняя успешная проверка
     * @param state           {@code ACTIVE}, {@code NEEDS_RECHECK}, {@code CLOSED}
     * @param unsuitable      отмечена пользователем «не подходит»
     */
    public record Publication(String url, Instant firstSeenAt, Instant lastConfirmedAt, String state,
            boolean unsuitable) {
    }
}
