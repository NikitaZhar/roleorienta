-- V9 — нормализованная локация публикации (§6). Сырое значение (raw_location, V6)
-- остаётся рядом; здесь — структура, разобранная из свободной строки локации.
-- Nullable-колонки (шаг expand, ADR-2). Правило честности (A01/§6): неизвестное
-- помечается явно, а не угадывается — work_modality = UNKNOWN при наличии локации
-- без слова remote/hybrid; NULL во всех колонках — локация не указана вовсе.
-- Город/страна разбираются эвристикой из строки вида «City, Country» и заполняются
-- только для обычного места (не remote/hybrid), иначе остаются NULL.

ALTER TABLE job_posting
    ADD COLUMN city          TEXT,   -- город из строки локации, либо NULL
    ADD COLUMN country       TEXT,   -- страна/регион из строки локации, либо NULL
    ADD COLUMN work_modality TEXT;   -- WorkModality: REMOTE|HYBRID|UNKNOWN
