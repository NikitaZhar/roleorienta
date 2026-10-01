-- Исправление ошибок обратного пути и чтения (стенограмма §36).
--
-- 1. Места публикации без ограничения длины. Workday отдаёт все места вакансии через «; »; у
--    вакансий во многих городах строка длиннее 500 символов, и запись публикации падала — чтение
--    всего источника завершалось неудачей. Обрезать нельзя: по местам определяется страна вакансии.
--    VARCHAR → TEXT в PostgreSQL не переписывает таблицу.
ALTER TABLE job_posting ALTER COLUMN location TYPE TEXT;

-- 2. Ключ доски — в нижнем регистре. Workday, Greenhouse, Personio и SmartRecruiters регистр в
--    адресе доски не различают; доска, найденная по ссылкам с разным регистром
--    (…/AccentureCareers и …/accenturecareers), записывалась дважды и читалась дважды — вакансии
--    задваивались. Остаётся источник с меньшим id; дубль удаляется вместе со своими чтениями,
--    снимками, публикациями и вакансиями (это копии вакансий оставшегося источника). Связь дубля с
--    компанией переносится на оставшийся источник. Объекты снимков дубля в хранилище остаются без
--    ссылки — их очистка вместе с прочими сиротами в долге (next-step.md, «Долг» п. 2).
CREATE TEMPORARY TABLE duplicate_source AS
SELECT s.id, (SELECT min(k.id) FROM source k
              WHERE k.provider = s.provider AND lower(k.board) = lower(s.board)) AS keeper_id
FROM source s
WHERE EXISTS (SELECT 1 FROM source k
              WHERE k.provider = s.provider AND lower(k.board) = lower(s.board) AND k.id < s.id);

-- Вакансии только из публикаций дублей (у вакансии с публикацией другого источника она остаётся).
CREATE TEMPORARY TABLE duplicate_vacancy AS
SELECT DISTINCT p.vacancy_id AS id
FROM job_posting p
WHERE p.source_id IN (SELECT id FROM duplicate_source)
  AND NOT EXISTS (SELECT 1 FROM job_posting other
                  WHERE other.vacancy_id = p.vacancy_id
                    AND other.source_id NOT IN (SELECT id FROM duplicate_source));

INSERT INTO company_source (company_id, source_id, found_at)
SELECT cs.company_id, d.keeper_id, cs.found_at
FROM company_source cs JOIN duplicate_source d ON d.id = cs.source_id
ON CONFLICT (company_id, source_id) DO NOTHING;
DELETE FROM company_source WHERE source_id IN (SELECT id FROM duplicate_source);

DELETE FROM vacancy_revision
WHERE vacancy_id IN (SELECT id FROM duplicate_vacancy)
   OR job_posting_id IN (SELECT id FROM job_posting WHERE source_id IN (SELECT id FROM duplicate_source))
   OR crawl_run_id IN (SELECT id FROM crawl_run WHERE source_id IN (SELECT id FROM duplicate_source));
DELETE FROM source_snapshot
WHERE crawl_run_id IN (SELECT id FROM crawl_run WHERE source_id IN (SELECT id FROM duplicate_source));
DELETE FROM crawl_run WHERE source_id IN (SELECT id FROM duplicate_source);
DELETE FROM job_posting WHERE source_id IN (SELECT id FROM duplicate_source);

DELETE FROM vacancy_position_match WHERE vacancy_id IN (SELECT id FROM duplicate_vacancy);
DELETE FROM delivered_vacancy WHERE vacancy_id IN (SELECT id FROM duplicate_vacancy);
DELETE FROM unsuitable_mark WHERE vacancy_id IN (SELECT id FROM duplicate_vacancy);
DELETE FROM vacancy WHERE id IN (SELECT id FROM duplicate_vacancy);

DELETE FROM source WHERE id IN (SELECT id FROM duplicate_source);
DROP TABLE duplicate_vacancy;
DROP TABLE duplicate_source;
UPDATE source SET board = lower(board) WHERE board <> lower(board);

-- Найденные доски: из записей одной доски с разным регистром остаётся одна, ключ — в нижнем регистре.
DELETE FROM discovered_board d
USING discovered_board k
WHERE k.provider = d.provider AND lower(k.board) = lower(d.board) AND k.board < d.board;
UPDATE discovered_board SET board = lower(board) WHERE board <> lower(board);
