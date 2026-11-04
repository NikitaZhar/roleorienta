-- Одна вакансия — одна запись (бизнес-описание §4.3, решение владельца 2026-11-03; стенограмма §256, §260).
--
-- 1. Название для сравнения (same_title, V90) дополнительно без зарплаты («1 500 €», «od 1200 EUR/mes.», «€ 900»)
--    и без пометки пола вне скобок («muž/žena», «m/ž/d»): то же название в разных каналах пишут по-разному. Одно
--    определение для job-worker (склейка при записи, отсев profesia.sk §220), job-api и показателей полноты.
--    В теле функции нет «;» — файл выполняет и тест склейки (ScriptUtils делит текст по «;»).
CREATE OR REPLACE FUNCTION same_title(title TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT btrim(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(
           lower(translate(title,
               'áäčďéěíĺľňóôŕřšťúůýžÁÄČĎÉĚÍĹĽŇÓÔŔŘŠŤÚŮÝŽ', 'aacdeeillnoorrstuuyzaacdeeillnoorrstuuyz')),
           '\([^)]*\)', ' ', 'g'),
           '(\mod\s+)?\d[\d .,]*(\s*-\s*\d[\d .,]*)?(,\s*-)?\s*(€|\meur\M)(\s*/\s*[a-z]+\.?)?(\s*(brutto|netto)\M)?', ' ', 'g'),
           '(€|\meur\M)\s*\d[\d .,]*', ' ', 'g'),
           '\m(muz|zena|m|z|f|w|d)(\s*/\s*(muz|zena|m|z|f|w|d))+\M', ' ', 'g'),
           '[^a-z0-9]+', ' ', 'g'))
$$;

-- 2. Накопленные дубли склеиваются по тому же правилу, что и новые публикации (SameVacancy): незакрытые вакансии
--    одного работодателя (компания — связь источника кроме роли GROUP; источник без компании — название
--    работодателя source.employer_name по same_title), то же название (same_title названия вакансии) и то же место
--    (same_title места публикации). Место есть только у одной — одна вакансия; у работодателя это название в разных
--    местах — склеиваются только вакансии одного места, вакансия без места остаётся отдельной («при сомнении — две
--    записи»). Склеиваются только вакансии разных источников: группа, где у одного источника две вакансии
--    (5 × «Operátor výroby» в одном списке), не трогается. Вакансия нескольких работодателей не трогается.
--    Остаётся вакансия с наименьшим id; на неё переносятся публикации, история, выдача, сводка на почту и отметки
--    пользователя (из двух отметок одного пользователя — дальше по пути отклика: Employer declined > Interview >
--    Applied > Not interested > Viewed, затем последняя; решение владельца 2026-11-04), история статусов — вся.
--    Соответствие позициям и требования к языку пересчитывает сопоставление (match_version сброшен).
CREATE TEMPORARY TABLE merge_posting AS
SELECT p.vacancy_id, p.source_id,
       coalesce('c' || cs.company_id, 'n' || same_title(s.employer_name)) AS owner,
       same_title(v.title) AS title_key,
       same_title(coalesce(p.location, '')) AS place_key
FROM job_posting p
JOIN vacancy v ON v.id = p.vacancy_id AND v.state <> 'CLOSED'
JOIN source s ON s.id = p.source_id
LEFT JOIN company_source cs ON cs.source_id = p.source_id AND cs.role <> 'GROUP'
WHERE p.closed_at IS NULL
  AND (cs.company_id IS NOT NULL
       OR s.employer_name IS NOT NULL AND NOT EXISTS (SELECT 1 FROM company_source x WHERE x.source_id = s.id));

CREATE TEMPORARY TABLE merge_vacancy AS
SELECT vacancy_id, min(owner) AS owner, min(title_key) AS title_key,
       coalesce(min(nullif(place_key, '')), '') AS place_key
FROM merge_posting
GROUP BY vacancy_id
HAVING count(DISTINCT owner) = 1 AND min(title_key) <> '';

CREATE TEMPORARY TABLE merge_cluster AS
SELECT v.vacancy_id, v.owner, v.title_key, CASE WHEN g.places <= 1 THEN '' ELSE v.place_key END AS cluster_place
FROM merge_vacancy v
JOIN (SELECT owner, title_key, count(DISTINCT nullif(place_key, '')) AS places
      FROM merge_vacancy GROUP BY owner, title_key) g ON g.owner = v.owner AND g.title_key = v.title_key
WHERE g.places <= 1 OR v.place_key <> '';

CREATE TEMPORARY TABLE merge_map AS
WITH valid AS (
    SELECT c.owner, c.title_key, c.cluster_place, min(c.vacancy_id) AS keeper_id
    FROM merge_cluster c
    JOIN (SELECT DISTINCT vacancy_id, source_id FROM job_posting) p ON p.vacancy_id = c.vacancy_id
    GROUP BY c.owner, c.title_key, c.cluster_place
    HAVING count(DISTINCT c.vacancy_id) > 1 AND count(*) = count(DISTINCT p.source_id))
SELECT c.vacancy_id, valid.keeper_id
FROM merge_cluster c
JOIN valid ON valid.owner = c.owner AND valid.title_key = c.title_key AND valid.cluster_place = c.cluster_place
WHERE c.vacancy_id <> valid.keeper_id;

UPDATE job_posting p SET vacancy_id = m.keeper_id FROM merge_map m WHERE p.vacancy_id = m.vacancy_id;
UPDATE vacancy_revision r SET vacancy_id = m.keeper_id FROM merge_map m WHERE r.vacancy_id = m.vacancy_id;
UPDATE vacancy_status_history h SET vacancy_id = m.keeper_id FROM merge_map m WHERE h.vacancy_id = m.vacancy_id;

INSERT INTO delivered_vacancy (search_condition_id, vacancy_id, pass_run_id, delivered_at)
SELECT d.search_condition_id, m.keeper_id, min(d.pass_run_id), min(d.delivered_at)
FROM delivered_vacancy d JOIN merge_map m ON m.vacancy_id = d.vacancy_id
GROUP BY d.search_condition_id, m.keeper_id
ON CONFLICT (search_condition_id, vacancy_id)
    DO UPDATE SET pass_run_id = CASE WHEN EXCLUDED.delivered_at < delivered_vacancy.delivered_at
                                     THEN EXCLUDED.pass_run_id ELSE delivered_vacancy.pass_run_id END,
                  delivered_at = least(delivered_vacancy.delivered_at, EXCLUDED.delivered_at);
DELETE FROM delivered_vacancy WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map);

INSERT INTO digest_vacancy (user_id, vacancy_id, mailed_at)
SELECT d.user_id, m.keeper_id, min(d.mailed_at)
FROM digest_vacancy d JOIN merge_map m ON m.vacancy_id = d.vacancy_id
GROUP BY d.user_id, m.keeper_id
ON CONFLICT (user_id, vacancy_id) DO NOTHING;
DELETE FROM digest_vacancy WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map);

CREATE TEMPORARY TABLE merge_status AS
SELECT DISTINCT ON (s.user_id, coalesce(m.keeper_id, s.vacancy_id))
       s.user_id, coalesce(m.keeper_id, s.vacancy_id) AS vacancy_id, s.status, s.changed_at
FROM vacancy_user_status s
LEFT JOIN merge_map m ON m.vacancy_id = s.vacancy_id
WHERE s.vacancy_id IN (SELECT vacancy_id FROM merge_map UNION SELECT keeper_id FROM merge_map)
ORDER BY s.user_id, coalesce(m.keeper_id, s.vacancy_id),
         CASE s.status WHEN 'REJECTED' THEN 5 WHEN 'INTERVIEW' THEN 4 WHEN 'APPLIED' THEN 3
                       WHEN 'NOT_INTERESTED' THEN 2 ELSE 1 END DESC,
         s.changed_at DESC;
DELETE FROM vacancy_user_status
WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map UNION SELECT keeper_id FROM merge_map);
INSERT INTO vacancy_user_status (user_id, vacancy_id, status, changed_at)
SELECT user_id, vacancy_id, status, changed_at FROM merge_status;

UPDATE vacancy k SET posted_at = least(k.posted_at, g.posted_at), first_seen_at = least(k.first_seen_at, g.first_seen_at),
                     last_confirmed_at = greatest(k.last_confirmed_at, g.last_confirmed_at),
                     state = CASE WHEN g.active THEN 'ACTIVE' ELSE k.state END,
                     match_version = NULL
FROM (SELECT m.keeper_id, min(v.posted_at) AS posted_at, min(v.first_seen_at) AS first_seen_at,
             max(v.last_confirmed_at) AS last_confirmed_at, bool_or(v.state = 'ACTIVE') AS active
      FROM merge_map m JOIN vacancy v ON v.id = m.vacancy_id GROUP BY m.keeper_id) g
WHERE k.id = g.keeper_id;

DELETE FROM vacancy_position_match WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map);
DELETE FROM vacancy_language WHERE vacancy_id IN (SELECT vacancy_id FROM merge_map);
DELETE FROM vacancy WHERE id IN (SELECT vacancy_id FROM merge_map);

DROP TABLE merge_status;
DROP TABLE merge_map;
DROP TABLE merge_cluster;
DROP TABLE merge_vacancy;
DROP TABLE merge_posting;
