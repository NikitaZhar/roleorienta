-- Распознавание позиции вакансии языковой моделью (технический документ §15, MVP п. 5б, шаг 1; стенограмма §261, §262).
--   title_classification — ответ модели на название вакансии: группа ISCO-08 (уровень 4) или «не понять» (NULL).
--     title_key     — название для сравнения (same_title, V92): одинаковые названия разных вакансий и стран — один
--                     вопрос модели; ответ хранится и повторно не спрашивается.
--     code          — группа ISCO-08; NULL — модель ответила, что это не вакансия или профессию не определить.
--     model         — какая модель ответила (смена модели не стирает прежние ответы).
--     classified_at — когда.
--   Ответ модели используется, только если у публикации нет кода портала (§203) и нет выученной головы названия или
--   однозначного синонима ESCO (§215): job_posting.title_occupation_code — как и прежде, одна колонка распознанной
--   группы, её видит представление search_condition_match.
CREATE TABLE title_classification (
    title_key     TEXT         PRIMARY KEY,
    code          VARCHAR(4)   REFERENCES occupation_group (code),
    model         VARCHAR(80)  NOT NULL,
    classified_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
