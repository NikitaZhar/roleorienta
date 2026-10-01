-- Перечитать доски Workday со страной сбора (стенограмма §37).
--
-- Доска Workday без фасета страны (AstraZeneca) читалась целиком: записаны публикации всех стран
-- (1029, из них 4 в Словакии). Теперь такая доска читается с фильтром по фасету мест страны. Уже
-- записанные публикации других стран из отфильтрованного списка пропадут, но проверка деталью
-- (публикация на доске есть) держала бы их открытыми. Поэтому публикации и вакансии досок Workday со
-- страной удаляются; следующее чтение (раз в час) запишет их заново — уже только страны сбора.
-- Обходы и снимки остаются как история чтений; история изменений удаляемых публикаций — вместе с ними.
CREATE TEMPORARY TABLE reread_posting AS
SELECT p.id, p.vacancy_id
FROM job_posting p JOIN source s ON s.id = p.source_id
WHERE s.provider = 'workday' AND s.country IS NOT NULL;

-- Вакансии только из этих публикаций.
CREATE TEMPORARY TABLE reread_vacancy AS
SELECT DISTINCT r.vacancy_id AS id
FROM reread_posting r
WHERE NOT EXISTS (SELECT 1 FROM job_posting other
                  WHERE other.vacancy_id = r.vacancy_id
                    AND other.id NOT IN (SELECT id FROM reread_posting));

DELETE FROM vacancy_revision
WHERE job_posting_id IN (SELECT id FROM reread_posting)
   OR vacancy_id IN (SELECT id FROM reread_vacancy);
DELETE FROM job_posting WHERE id IN (SELECT id FROM reread_posting);
DELETE FROM vacancy_position_match WHERE vacancy_id IN (SELECT id FROM reread_vacancy);
DELETE FROM delivered_vacancy WHERE vacancy_id IN (SELECT id FROM reread_vacancy);
DELETE FROM unsuitable_mark WHERE vacancy_id IN (SELECT id FROM reread_vacancy);
DELETE FROM vacancy WHERE id IN (SELECT id FROM reread_vacancy);

DROP TABLE reread_vacancy;
DROP TABLE reread_posting;
