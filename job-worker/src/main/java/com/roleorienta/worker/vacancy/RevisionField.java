package com.roleorienta.worker.vacancy;

/**
 * Что изменилось в публикации (запись истории вакансии).
 */
public enum RevisionField {
    /** Позиция. */
    TITLE,
    /** Место работы. */
    LOCATION,
    /** Закрытая публикация появилась снова — та же вакансия снова актуальна. */
    REOPENED
}
