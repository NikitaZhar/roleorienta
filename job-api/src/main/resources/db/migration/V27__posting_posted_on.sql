-- V27 — данные детали Workday (§65): дата публикации по источнику и дополнительные локации.
-- Nullable-колонки (шаг expand, ADR-2). posted_on — дата, которую сообщает источник
-- (Workday startDate), в отличие от first_seen_at (когда мы увидели); NULL — не сообщается.
-- additional_locations — прочие локации многолокационной вакансии через «; ».
-- work_modality (V9) получает значение ONSITE — только при явном сообщении источника.

ALTER TABLE job_posting
    ADD COLUMN posted_on            DATE,
    ADD COLUMN additional_locations TEXT;

-- Лента сортируется/фильтруется по свежести (следующие срезы).
CREATE INDEX idx_job_posting_posted_on ON job_posting (posted_on);
