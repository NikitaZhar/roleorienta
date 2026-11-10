-- Разделение ошибочной склейки вакансий по журналу vacancy_merge (docs/vacancy-identity-plan.md §5, стенограмма §280).
-- Возвращает присоединённую вакансию: её строку, публикации (где бы они ни были сейчас), историю публикаций и
-- статусов, отметки пользователей, выдачу и сводку; пара записывается в vacancy_merge_block — пересмотр её больше не
-- склеивает; строка журнала отмечается undone_at. Отметка пользователя на оставшейся вакансии (из двух — дальше по пути
-- отклика) остаётся как есть. Сопоставление обеих вакансий пересчитывается (match_version сброшен).
-- Найти склейку: SELECT id, kept_vacancy_id, merged_vacancy_id, signal, merged_at FROM vacancy_merge
--                WHERE kept_vacancy_id = <вакансия> AND undone_at IS NULL;
-- Запуск (job-worker можно не останавливать — блокировка записи вакансий, §271):
-- docker compose exec -T postgres psql -U roleorienta -d roleorienta -v ON_ERROR_STOP=1 -v merge_id=<id> < ops/vacancy-unmerge.sql
BEGIN;
SELECT pg_advisory_xact_lock(1025);

CREATE TEMPORARY TABLE unmerge ON COMMIT DROP AS
SELECT * FROM vacancy_merge WHERE id = :merge_id AND undone_at IS NULL;
-- нет такой склейки или уже разделена — ошибка (деление на ноль), транзакция откатывается
SELECT 1 / (SELECT count(*) FROM unmerge)::int AS merge_found;

INSERT INTO vacancy SELECT (jsonb_populate_record(NULL::vacancy, u.merged_vacancy)).* FROM unmerge u;
UPDATE job_posting p SET vacancy_id = u.merged_vacancy_id FROM unmerge u WHERE p.id = ANY (u.postings);
UPDATE vacancy_revision r SET vacancy_id = u.merged_vacancy_id FROM unmerge u WHERE r.job_posting_id = ANY (u.postings);
UPDATE vacancy_status_history h SET vacancy_id = u.merged_vacancy_id FROM unmerge u WHERE h.id = ANY (u.status_history);
INSERT INTO vacancy_user_status
SELECT (jsonb_populate_record(NULL::vacancy_user_status, x)).*
FROM unmerge u, jsonb_array_elements(coalesce(u.user_marks, '[]'::jsonb)) x
ON CONFLICT DO NOTHING;
INSERT INTO delivered_vacancy
SELECT (jsonb_populate_record(NULL::delivered_vacancy, x)).*
FROM unmerge u, jsonb_array_elements(coalesce(u.delivered, '[]'::jsonb)) x
ON CONFLICT DO NOTHING;
INSERT INTO digest_vacancy
SELECT (jsonb_populate_record(NULL::digest_vacancy, x)).*
FROM unmerge u, jsonb_array_elements(coalesce(u.digest, '[]'::jsonb)) x
ON CONFLICT DO NOTHING;

-- название и ссылка обеих — от главной публикации (первая открытая не с profesia.sk, нет такой — первая), сопоставление
UPDATE vacancy k SET title = main.title, primary_url = main.url, match_version = NULL
FROM (SELECT DISTINCT ON (p.vacancy_id) p.vacancy_id, p.title, p.url
      FROM job_posting p, unmerge u
      WHERE p.vacancy_id IN (u.kept_vacancy_id, u.merged_vacancy_id)
      ORDER BY p.vacancy_id, p.closed_at IS NOT NULL, p.external_id LIKE 'profesia:%', p.id) main
WHERE k.id = main.vacancy_id;

INSERT INTO vacancy_merge_block (vacancy_a, vacancy_b)
SELECT least(kept_vacancy_id, merged_vacancy_id), greatest(kept_vacancy_id, merged_vacancy_id) FROM unmerge
ON CONFLICT DO NOTHING;
UPDATE vacancy_merge SET undone_at = now() WHERE id = :merge_id;

SELECT kept_vacancy_id, merged_vacancy_id, signal, cardinality(postings) AS postings_returned FROM unmerge;
COMMIT;
