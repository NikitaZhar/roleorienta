package com.roleorienta.api.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Данные запросов и ответов о компаниях и адресах кадровых страниц (§137; бизнес-описание §4.2, §5).
 */
public final class CompanyDtos {

    private CompanyDtos() {
    }

    /**
     * Компания.
     *
     * @param id                 id компании
     * @param name               название из реестра
     * @param registrationNumber регистрационный номер (IČO)
     * @param check              группа и итог проверки
     */
    public record CompanyItem(long id, String name, String registrationNumber, Check check) {
    }

    /**
     * Группа компании (бизнес-описание §4.2) и итог проверки.
     *
     * @param group     {@code YES} — своя кадровая страница или система найма читается; {@code PORTAL} — только
     *                  вакансии государственного портала (§143); {@code MAYBE} — сайт найден, кадровой страницы нет
     *                  или она не читается; {@code NO} — сайт не найден; {@code NOT_EMPLOYER} — отметка
     *                  администратора «не работодатель» (§138)
     * @param reason    итог проверки ({@code SITE_NOT_FOUND}, {@code PAGE_NOT_FOUND}, {@code FORMAT_UNSUPPORTED},
     *                  {@code SOURCE_UNAVAILABLE}, {@code USE_FORBIDDEN}); {@code null} — подключена или не проверена
     * @param site      хост найденного сайта; {@code null} — нет
     * @param careerUrl адрес найденной кадровой страницы; {@code null} — нет
     */
    public record Check(String group, String reason, String site, String careerUrl) {
    }

    /**
     * Страница списка компаний по номеру (§163).
     *
     * @param items компании
     * @param page  номер страницы, с 1
     * @param size  строк на странице
     * @param total сколько компаний во всём списке
     */
    public record CompanyPage(List<CompanyItem> items, int page, int size, long total) {
    }

    /**
     * Адрес кадровой страницы, введённый пользователем.
     *
     * @param url адрес http(s), до 2000 символов
     */
    public record CareerPageInput(@NotBlank @Size(max = 2000) String url) {
    }

    /**
     * Запрос пользователя на адрес кадровой страницы.
     *
     * @param id          запрос
     * @param companyName название компании
     * @param url         введённый адрес
     * @param status      состояние
     */
    public record CareerRequestItem(long id, String companyName, String url, Status status) {
    }

    /**
     * Состояние запроса.
     *
     * @param state     {@code PENDING} — в обработке, {@code CONNECTED} — подключена, {@code NOT_CONNECTED} — нет
     * @param reason    причина неудачи ({@code UNREACHABLE}, {@code USE_FORBIDDEN}, {@code NO_CAREER_PAGE},
     *                  {@code FORMAT_UNSUPPORTED}); {@code null} — нет
     * @param createdAt когда введён
     */
    public record Status(String state, String reason, Instant createdAt) {
    }
}
