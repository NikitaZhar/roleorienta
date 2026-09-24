-- V28 — ключ идентичности работодателя (A3, §80): одна компания на пару (провайдер, тенант).
-- Два сайта одного тенанта Workday (lilly/LLY и lilly/External) — один работодатель и две
-- доски. Ключ — «<код провайдера>:<slug до первого '/' в нижнем регистре>» (Company.identityKey).
-- Nullable (шаг expand, ADR-2): у компаний, заведённых не обнаружением, ключа нет. UNIQUE в
-- PostgreSQL допускает много NULL.

ALTER TABLE company ADD COLUMN identity_key TEXT;
ALTER TABLE company ADD CONSTRAINT uq_company_identity_key UNIQUE (identity_key);

-- Уже заведённые из обнаружения компании: ключ получает старшая (с меньшим id) компания
-- каждого тенанта; прочие остаются без ключа — их связи с источниками не трогаются.
UPDATE company c
SET identity_key = k.identity_key
FROM (
    SELECT DISTINCT ON (ik.identity_key) cs.company_id, ik.identity_key
    FROM company_source cs
    JOIN source s ON s.id = cs.source_id
    JOIN provider p ON p.id = s.provider_id
    CROSS JOIN LATERAL (
        SELECT p.code || ':' || lower(split_part(s.external_ref, '/', 1)) AS identity_key
    ) ik
    ORDER BY ik.identity_key, cs.company_id
) k
WHERE c.id = k.company_id;
