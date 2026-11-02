package com.roleorienta.api.admin;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/**
 * Данные запросов и ответов администратора (§132).
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * Вход администратора.
     *
     * @param username имя
     * @param password пароль
     */
    public record AdminCredentials(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * Вошедший администратор.
     *
     * @param username имя
     */
    public record Admin(String username) {
    }

    /**
     * Показатели ядра страны по дням.
     *
     * @param country   страна таблицы: код ISO, {@code *} — весь мир, пусто — без страны
     * @param countries страны, за которые есть показатели
     * @param days      дни, новые первыми ({@code 2026-10-18})
     * @param rows      строки: показатель и разрез, значения по дням
     */
    public record MetricsTable(String country, List<String> countries, List<String> days, List<MetricRow> rows) {
    }

    /**
     * Строка показателя.
     *
     * @param metric    код показателя (технический документ §11)
     * @param dimension разрез; пусто — итог показателя
     * @param values    значения по дням {@link MetricsTable#days()}; {@code null} — за день строки нет
     */
    public record MetricRow(String metric, String dimension, List<Long> values) {
    }

    /**
     * Состояние ядра сейчас (§142): видно, что ядро остановилось.
     *
     * @param checkedAt момент проверки (часы БД)
     * @param reads     чтения источников
     * @param tasks     задания
     * @param warnings  тревоги: {@code NO_RECENT_READ} — успешного чтения нет дольше 2 ч; {@code QUEUE_STALLED} —
     *                  задание ждёт в очереди дольше 2 ч
     */
    public record CoreStatus(Instant checkedAt, ReadStats reads, TaskStats tasks, List<String> warnings) {
    }

    /**
     * Чтения источников.
     *
     * @param lastSuccessAt  конец последнего чтения, полного или неполного; {@code null} — чтений не было
     * @param lastHour       чтений, полных или неполных, за последний час
     * @param failedLastHour отказов чтения за последний час
     */
    public record ReadStats(Instant lastSuccessAt, long lastHour, long failedLastHour) {
    }

    /**
     * Задания.
     *
     * @param queued         в очереди ({@code QUEUED})
     * @param oldestQueuedAt самый давний вход в очередь среди них; {@code null} — очередь пуста
     * @param failedLastDay  не выполнены после всех попыток за сутки ({@code FAILED})
     */
    public record TaskStats(long queued, Instant oldestQueuedAt, long failedLastDay) {
    }

    /**
     * Источник вакансий без компании (§206, «Sources without company»): по нему администратор решает, закрыть ли его.
     *
     * @param id           источник
     * @param provider     провайдер ({@code careerpage}, {@code greenhouse} …)
     * @param board        доска: адрес страницы или код у системы найма
     * @param openPostings открытых публикаций
     * @param titles       до 5 названий открытых публикаций — видно, вакансии ли это
     */
    public record UnlinkedSource(long id, String provider, String board, long openPostings, List<String> titles) {
    }

    /**
     * Компания, у которой теряются вакансии (§235): следующая для администратора.
     *
     * @param company компания и числа полноты сбора
     * @param sites   найденные сайты компании
     * @param missing     до 5 вакансий портала и profesia.sk, которых нет на её собственной странице
     * @param lastAddress последний добавленный адрес кадровой страницы и итог его проверки (§243); {@code null} — нет
     */
    public record Loss(LossCompany company, List<LossSite> sites, List<LossTitle> missing, LossAddress lastAddress) {
    }

    /**
     * Адрес кадровой страницы, добавленный пользователем или администратором (§243).
     *
     * @param url    адрес
     * @param state  {@code PENDING}, {@code CONNECTED}, {@code NOT_CONNECTED}
     * @param reason причина «не подключена»; иначе {@code null}
     */
    public record LossAddress(String url, String state, String reason) {
    }

    /**
     * @param id                 компания
     * @param registrationNumber IČO
     * @param name               название
     * @param counts             полнота сбора (последний пересчёт показателей)
     * @param remaining          сколько компаний с потерями в очереди, включая эту
     */
    public record LossCompany(long id, String registrationNumber, String name, LossCounts counts, long remaining) {
    }

    /**
     * Полнота сбора компании ({@code company_coverage}, §234).
     *
     * @param portalVacancies разных вакансий портала и profesia.sk
     * @param foundOnOwn      из них найдено на собственной странице
     * @param ownVacancies    вакансий собственной страницы
     * @param channel         состояние собственного канала ({@code READ}, {@code NO_CAREER_PAGE}, {@code NO_SITE} …)
     */
    public record LossCounts(int portalVacancies, int foundOnOwn, int ownVacancies, String channel) {
    }

    /**
     * @param host        хост сайта
     * @param checkResult итог проверки сайта; {@code null} — не проверен
     * @param careerUrl   найденная кадровая страница; {@code null} — нет
     */
    public record LossSite(String host, String checkResult, String careerUrl) {
    }

    /**
     * @param title название вакансии
     * @param url   публикация на портале или profesia.sk
     */
    public record LossTitle(String title, String url) {
    }
}
