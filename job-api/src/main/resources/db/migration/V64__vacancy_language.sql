-- Требования к языку у вакансии (бизнес-описание §6; стенограмма §158): язык (ISO 639-1) и обязателен он или
-- желателен — из текста публикаций, предрасчёт job-worker (MATCH_VACANCIES, вместе со страной и форматом). Строк нет —
-- «не указано» (текста нет или язык в нём не назван). Строки вакансии заменяются вместе с отметкой версии
-- сопоставления; версия правил языков входит в версию сопоставления — после выкладки все вакансии пересчитываются.
CREATE TABLE vacancy_language (
    vacancy_id BIGINT     NOT NULL REFERENCES vacancy (id),
    language   VARCHAR(2) NOT NULL CHECK (language ~ '^[a-z]{2}$'),
    required   BOOLEAN    NOT NULL,
    PRIMARY KEY (vacancy_id, language)
);
