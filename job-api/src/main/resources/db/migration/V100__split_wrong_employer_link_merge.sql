-- Разъединение вакансий, ошибочно склеенных склейкой §268 до исправления (стенограмма §270, аудит §268 замечание 1).
--   Признак ущерба: в вакансии больше одной публикации одного источника — склейка при записи (§139, §260) и V92 так не
--   делают (2026-11-06: 10 вакансий — Slovnaft/MOL: 2 вакансии Taleo и разные объявления портала; объявления разных
--   названий или городов с кнопкой на одну общую страницу).
--   В каждой такой вакансии остаются публикации с названием самой вакансии (same_title) — по одной на источник (с
--   наименьшим id); нет такой ни одной — остаётся публикация с наименьшим id. Остальные получают каждая свою вакансию:
--   название и ссылка — публикации, даты — её, состояние — открыта или закрыта, как публикация. Отметки пользователей,
--   выдача и история остаются на исходной вакансии; соответствие позициям пересчитывается (match_version сброшен).
CREATE TEMPORARY TABLE split_vacancy AS
SELECT DISTINCT vacancy_id FROM job_posting GROUP BY vacancy_id, source_id HAVING count(*) > 1;

CREATE TEMPORARY TABLE split_keep AS
SELECT DISTINCT ON (p.vacancy_id, p.source_id) p.id
FROM job_posting p JOIN vacancy v ON v.id = p.vacancy_id
WHERE p.vacancy_id IN (SELECT vacancy_id FROM split_vacancy) AND same_title(p.title) = same_title(v.title)
ORDER BY p.vacancy_id, p.source_id, p.id;

INSERT INTO split_keep
SELECT min(p.id) FROM job_posting p
WHERE p.vacancy_id IN (SELECT vacancy_id FROM split_vacancy)
GROUP BY p.vacancy_id
HAVING NOT bool_or(p.id IN (SELECT id FROM split_keep));

DO $$
DECLARE
    moved RECORD;
    created BIGINT;
BEGIN
    FOR moved IN
        SELECT p.id, p.title, p.url, p.first_seen_at, p.last_confirmed_at, p.closed_at
        FROM job_posting p
        WHERE p.vacancy_id IN (SELECT vacancy_id FROM split_vacancy) AND p.id NOT IN (SELECT id FROM split_keep)
        ORDER BY p.id
    LOOP
        INSERT INTO vacancy (state, title, primary_url, first_seen_at, last_confirmed_at, closed_at)
        VALUES (CASE WHEN moved.closed_at IS NULL THEN 'ACTIVE' ELSE 'CLOSED' END, moved.title, moved.url,
                moved.first_seen_at, moved.last_confirmed_at, moved.closed_at)
        RETURNING id INTO created;
        UPDATE job_posting SET vacancy_id = created WHERE id = moved.id;
    END LOOP;
END $$;

UPDATE vacancy SET match_version = NULL WHERE id IN (SELECT vacancy_id FROM split_vacancy);

DROP TABLE split_keep;
DROP TABLE split_vacancy;
