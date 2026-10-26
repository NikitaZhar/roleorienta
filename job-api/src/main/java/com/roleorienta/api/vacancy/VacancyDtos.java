package com.roleorienta.api.vacancy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Ответы и запросы API накопленного списка, сведений и статусов вакансии (технический документ §8; бизнес-описание §6). Отсутствующие
 * сведения — {@code null} («не указано»); полный текст вакансии не возвращается.
 */
public final class VacancyDtos {

    private VacancyDtos() {
    }

    /**
     * Вакансия в списке.
     *
     * @param id       вакансия
     * @param posting  позиция и адрес первичной публикации (§192: публикация открывается прямо из строки)
     * @param parties  работодатель и агентство
     * @param work     страна и формат работы
     * @param timeline когда вакансия открыта и её статус у пользователя
     */
    public record VacancyItem(long id, Posting posting, Parties parties, Work work, Timeline timeline) {
    }

    /**
     * Публикация вакансии в строке списка (§192).
     *
     * @param position позиция (название публикации)
     * @param url      адрес первичной публикации
     * @param matches  к каким позициям условий подошла (§194; названия по алфавиту); в «Моих откликах» и «Не
     *                 интересно» — пусто
     */
    public record Posting(String position, String url, List<String> matches) {
    }

    /**
     * Позиция условий и сколько видимых вакансий к ней подходит (§194: метки отбора над результатами).
     *
     * @param code  код позиции
     * @param name  название
     * @param count сколько вакансий
     */
    public record PositionCount(String code, String name, long count) {
    }

    /**
     * Когда вакансия открыта и её статус у пользователя (§156).
     *
     * @param opened         когда открыта: дата размещения работодателем или «найдена» (§127)
     * @param status         текущий статус пользователя
     * @param sinceLastVisit впервые получена пользователем после его прошлого захода в результаты (§191; точка у
     *                       даты); первый заход, другие списки и сведения о вакансии — {@code false}
     */
    public record Timeline(Opened opened, UserStatus status, boolean sinceLastVisit) {
    }

    /**
     * Текущий статус вакансии у пользователя (бизнес-описание §6, §156).
     *
     * @param code   {@code NEW} — «не открыта» (пришла в результаты, публикацию из результатов ещё не открывали),
     *               иначе статус {@link StatusEvent}: {@code VIEWED}, {@code NOT_INTERESTED}, {@code APPLIED},
     *               {@code INTERVIEW}, {@code REJECTED}
     * @param at     когда поставлен; у новой — когда вакансия впервые получена пользователем; {@code null} — не
     *               выдавалась (отметка без выдачи)
     * @param closed вакансия закрыта работодателем (в «Моих откликах» — пометка «закрыта»)
     */
    public record UserStatus(String code, Instant at, boolean closed) {
    }

    /**
     * Когда вакансия открыта (решение владельца 2026-10-16, §127).
     *
     * @param at         дата размещения работодателем (начало суток UTC); источник её не дал — момент первого
     *                   обнаружения вакансии системой
     * @param byEmployer {@code true} — дата от источника (работодатель, система найма); {@code false} — «найдена»
     */
    public record Opened(Instant at, boolean byEmployer) {
    }

    /**
     * Страница списка (страницы по номеру — решение владельца 2026-10-20, §151).
     *
     * @param items    вакансии: результаты — открытые позже сверху (§127); «Мои отклики» и «Не интересно» — статус
     *                 изменён позже сверху (§156)
     * @param page     номер страницы, с 1
     * @param size     строк на странице
     * @param total    сколько вакансий во всём списке
     * @param newCount сколько из них новых («новых M»; §156) — только у результатов поиска, у остальных списков 0
     */
    public record VacancyPage(List<VacancyItem> items, int page, int size, long total, long newCount) {
    }

    /**
     * Сведения о вакансии (бизнес-описание §6).
     *
     * @param id          вакансия
     * @param position    позиция
     * @param parties     работодатель и агентство
     * @param work        страна, формат, территория удалённой работы, неопределённость
     * @param publication ссылка, статус пользователя и история
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
     * @param countryUncertain страна не определена — стран нет: такая вакансия выдаётся, страна на экране не
     *                         показывается (решения владельца 2026-10-20, §148; §192)
     */
    public record Work(List<String> countries, String format, List<String> remoteTerritory, boolean countryUncertain) {
    }

    /**
     * Публикация, требования к языку из её текста и работа пользователя с вакансией. Источник, дата последней
     * проверки и состояние вакансии у работодателя не показываются (решение владельца 2026-10-20, бизнес-описание §6).
     *
     * @param url       первичная публикация
     * @param timeline  когда открыта и текущий статус
     * @param history   история по времени, раньше сверху
     * @param languages требования к языку из текста публикаций, по коду языка; пусто — «не указано» (§158)
     */
    public record Publication(String url, Timeline timeline, List<HistoryLine> history,
            List<LanguageRequirement> languages) {
    }

    /**
     * Требование к языку (бизнес-описание §6, §158).
     *
     * @param language код языка (ISO 639-1)
     * @param required {@code true} — обязателен, {@code false} — желателен
     */
    public record LanguageRequirement(String language, boolean required) {
    }

    /**
     * Строка истории работы с вакансией (§156).
     *
     * @param event {@code RECEIVED} — «Получена» (первая выдача), {@code CLOSED} — «Закрыта работодателем», иначе
     *              статус {@link StatusEvent} ({@code RETURNED} — «Возвращена» из «Не интересно»)
     * @param at    когда
     */
    public record HistoryLine(String event, Instant at) {
    }

    /**
     * Запрос смены статуса (§156).
     *
     * @param status статус; {@code VIEWED} ставит экран при открытии публикации из результатов (§193),
     *               {@code RETURNED} — «Вернуть» из «Не интересно»
     * @param zone   часовой пояс браузера (IANA, например {@code Europe/Bratislava}): по нему определяется день —
     *               статус того же дня заменяет прежний выбор этого дня (решение владельца 2026-10-21)
     */
    public record StatusRequest(@NotNull StatusEvent status, @NotBlank String zone) {
    }
}
