-- V13 — отношение вакансии к навыку: запрос / отрицание / миграция (§6, A08).
-- Шаг expand (ADR-2): A08 требует извлекать отрицания и миграции ОТДЕЛЬНО от
-- обязательности. Прежде «C# is not required» сливалось в modality=UNSPECIFIED; теперь
-- stance различает REQUESTED (запрашивается) / NEGATED (явно не нужен) / MIGRATION
-- (уходят от технологии). Для NEGATED/MIGRATION modality остаётся UNSPECIFIED.
--
-- NOT NULL DEFAULT 'REQUESTED': существующие строки (навыки без стенса) — обычный
-- запрос; при следующем сборе значение перезаписывается извлекателем.

ALTER TABLE posting_skill
    ADD COLUMN stance TEXT NOT NULL DEFAULT 'REQUESTED';
