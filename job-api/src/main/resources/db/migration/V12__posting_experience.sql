-- V12 — уровень опыта и минимальное число лет из описания (§6, A08).
-- Шаг expand (ADR-2): nullable-колонки-скаляры рядом с прочими нормализованными
-- полями публикации (как зарплата/локация); значения проставляет FETCH_POSTING.
--
-- A08: уровень (seniority) и число лет опыта хранятся РАЗДЕЛЬНО.
--   seniority: JUNIOR|MEDIOR|SENIOR|UNKNOWN — UNKNOWN, если из текста уровень
--     однозначно не следует (не упомянут или упомянуто несколько уровней), а не догадка.
--   experience_years_min: минимально требуемое число лет («N+ years», «at least N
--     years of experience», нижняя граница «N-M years»), либо NULL, если не указано.

ALTER TABLE job_posting
    ADD COLUMN seniority             TEXT,
    ADD COLUMN experience_years_min  INTEGER;
