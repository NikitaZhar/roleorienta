-- Страны общего сбора и чередование их партий (бизнес-описание §4.1, сценарий 4; технический документ
-- §4 CollectionCountry, §5.1; стенограмма §41).
--   active        — страна есть в активных условиях поиска хотя бы одного пользователя;
--   has_more      — у реестра страны есть что читать (тик снова ставит true у активных стран);
--   last_batch_at — время последней партии: следующей читается страна, чья партия старше всех;
--   batches       — число прочитанных партий (часть ключа задания партии).
-- Место обработки внутри реестра хранит сам реестр (у RPO — intake_cursor).
CREATE TABLE collection_country (
    country       CHAR(2)     PRIMARY KEY,
    active        BOOLEAN     NOT NULL DEFAULT FALSE,
    has_more      BOOLEAN     NOT NULL DEFAULT TRUE,
    last_batch_at TIMESTAMPTZ,
    batches       BIGINT      NOT NULL DEFAULT 0
);

-- Страны из активных условий пользователей.
INSERT INTO collection_country (country, active)
SELECT DISTINCT unnest(countries), TRUE FROM search_condition WHERE active
ON CONFLICT (country) DO NOTHING;

-- Незавершённые задания приёма прежнего вида (без страны в параметрах) больше не нужны: партии ставит
-- очередь стран.
UPDATE task SET state = 'DONE', updated_at = now()
WHERE type = 'REGISTRY_INTAKE' AND state IN ('QUEUED', 'WAITING', 'RUNNING');
