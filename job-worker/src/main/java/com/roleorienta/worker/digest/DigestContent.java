package com.roleorienta.worker.digest;

import com.roleorienta.core.domain.CoverageState;
import java.util.List;

/**
 * Что вошло в дайджест пользователя за окно (A7, §88): новые вакансии отслеживаемых компаний и
 * изменения в их вакансиях. Одна вакансия — одна строка, даже если пользователь подписан на
 * несколько её компаний или изменилось несколько полей (свёртка, technical-design A16).
 *
 * @param newPostings новые вакансии (сначала «только с сайта»)
 * @param changes     изменения известных вакансий
 */
public record DigestContent(List<NewPosting> newPostings, List<ChangedPosting> changes) {

    /**
     * Нечего отправлять.
     *
     * @return {@code true}, если нет ни новых вакансий, ни изменений
     */
    public boolean isEmpty() {
        return newPostings.isEmpty() && changes.isEmpty();
    }

    /**
     * Новая вакансия.
     *
     * @param title    заголовок
     * @param url      адрес вакансии у источника
     * @param company  компания
     * @param coverage оценка покрытия площадкой; нет оценки — {@link CoverageState#UNKNOWN}
     */
    public record NewPosting(String title, String url, String company, CoverageState coverage) {
    }

    /**
     * Изменённая вакансия.
     *
     * @param title   заголовок
     * @param url     адрес вакансии у источника
     * @param company компания
     * @param fields  изменённые поля ({@code notification.field_name}), без повторов
     */
    public record ChangedPosting(String title, String url, String company, List<String> fields) {
    }
}
