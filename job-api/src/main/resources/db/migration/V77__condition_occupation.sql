-- Позиции по ISCO-08 в условиях и выдаче (§204; шаг 5 — технический документ §15, MVP, п. 3б). В условиях — позиции
-- словаря (точные: «Java developer») и группы ISCO-08 (широкие: «Разработчики ПО»; 4 цифры).
--   position.occupation_code     — группа позиции словаря: позиция видна внутри группы при выборе, вакансии позиции
--                                  входят в группу. NULL — не задана (позиции, добавленные до этой миграции, кроме 22
--                                  исходных; задаётся в словаре администратора).
--   search_condition_occupation  — группы версии условий; удаляются вместе с версией.
--   search_condition_match       — к каким пунктам условий подходит вакансия; одно правило для выдачи и сводки на почту
--                                  (job-worker) и результатов (job-api). Позиция — вакансия сопоставлена ей. Группа —
--                                  у публикации вакансии код группы (государственный портал) или вакансия сопоставлена
--                                  позиции этой группы. Сравнение в момент чтения: код, полученный позже, действует сразу.
ALTER TABLE position ADD COLUMN occupation_code VARCHAR(4) REFERENCES occupation_group (code)
    CHECK (length(occupation_code) = 4);

UPDATE position p SET occupation_code = m.code FROM (VALUES
    ('java-developer', '2512'),
    ('dotnet-developer', '2512'),
    ('python-developer', '2512'),
    ('frontend-developer', '2513'),
    ('qa-engineer', '2519'),
    ('devops-engineer', '2512'),
    ('data-analyst', '2511'),
    ('data-engineer', '2511'),
    ('project-manager', '1219'),
    ('business-analyst', '2421'),
    ('accountant', '2411'),
    ('sales-representative', '3322'),
    ('hr-specialist', '2423'),
    ('customer-support', '4222'),
    ('platform-engineer', '2512'),
    ('architect', '2511'),
    ('data-architect', '2521'),
    ('ai-engineer', '2511'),
    ('engineering-manager', '1330'),
    ('application-engineer', '2514'),
    ('data-quality', '2519'),
    ('analytics-engineer', '2511')
) AS m (position_code, code) WHERE p.code = m.position_code;

CREATE TABLE search_condition_occupation (
    search_condition_id BIGINT     NOT NULL REFERENCES search_condition (id) ON DELETE CASCADE,
    code                VARCHAR(4) NOT NULL REFERENCES occupation_group (code) CHECK (length(code) = 4),
    PRIMARY KEY (search_condition_id, code)
);

CREATE INDEX job_posting_occupation_idx ON job_posting (occupation_code) WHERE occupation_code IS NOT NULL;

CREATE INDEX position_occupation_idx ON position (occupation_code);

-- code — код пункта условий: код позиции словаря или группы ISCO-08. Пара может повторяться (у вакансии и код
-- портала, и позиция группы).
CREATE VIEW search_condition_match AS
SELECT s.search_condition_id, p.code, m.vacancy_id
FROM search_condition_position s
JOIN position p ON p.id = s.position_id
JOIN vacancy_position_match m ON m.position_id = s.position_id
UNION ALL
SELECT o.search_condition_id, o.code, jp.vacancy_id
FROM search_condition_occupation o
JOIN job_posting jp ON jp.occupation_code = o.code
UNION ALL
SELECT o.search_condition_id, o.code, m.vacancy_id
FROM search_condition_occupation o
JOIN position p ON p.occupation_code = o.code
JOIN vacancy_position_match m ON m.position_id = p.id;
