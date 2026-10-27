-- Профессия по названию вакансии (§215; позиции по ISCO-08, шаг 4 — технический документ §15, MVP, п. 3б). Обучение —
-- на публикациях с кодом государственного портала (§203); прототип на данных 2026-10-27 (target/title-learning-2.sql):
-- точность 97,1 % на проверочной половине, в Словакии распознаётся около четверти вакансий без профессии.
--   title_head(title)               — «голова» названия: до первого разделителя (« - », «|», «,», «/», «(», «–»), без
--                                     регистра, словацкой диакритики и лишних пробелов («Kuchár/ka (muž/žena)» → «kuchar»).
--   title_occupation                — выученное: голова → группа ISCO-08, если у публикаций портала с этой головой (не
--                                     меньше двух) доля одной группы не меньше 70 %; пересчитывается раз в сутки.
--   job_posting.title_occupation_code — распознанная группа публикации без кода портала: выученная голова, иначе
--                                     однозначный синоним ESCO; отдельно от кода портала — обучение не идёт на догадках.
-- Группа условий подходит и к распознанной группе (новая ветвь представления search_condition_match).
CREATE FUNCTION title_head(title TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT btrim(regexp_replace(regexp_replace(translate(lower(title), 'áäčďéíĺľňóôŕšťúýž', 'aacdeillnoorstuyz'),
                                               '\s*([|,/(–]|\s-\s).*$', ''), '\s+', ' ', 'g'))
$$;

CREATE TABLE title_occupation (
    head     VARCHAR(500) PRIMARY KEY,
    code     VARCHAR(4)   NOT NULL REFERENCES occupation_group (code),
    postings INTEGER      NOT NULL CHECK (postings >= 2)
);

ALTER TABLE job_posting ADD COLUMN title_occupation_code VARCHAR(4) CHECK (title_occupation_code ~ '^[0-9]{4}$');

CREATE INDEX job_posting_title_occupation_idx ON job_posting (title_occupation_code)
    WHERE title_occupation_code IS NOT NULL;

CREATE OR REPLACE VIEW search_condition_match AS
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
JOIN vacancy_position_match m ON m.position_id = p.id
UNION ALL
SELECT o.search_condition_id, o.code, jp.vacancy_id
FROM search_condition_occupation o
JOIN job_posting jp ON jp.title_occupation_code = o.code;
