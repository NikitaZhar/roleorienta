-- V10 — текст описания вакансии и языковые требования (§6, A07).
-- Шаг expand (ADR-2): описание — nullable-колонка рядом с прочими сырыми полями;
-- языки — отдельная таблица (у языка своя модель A07, отличная от навыков/технологий).
--
-- Правило A07: факт упоминания (mentioned) и обязательность (modality) независимы;
-- «German-speaking team» = mentioned=YES, modality=UNSPECIFIED, а не «требуется».
-- Отсутствие строки по языку = «не упомянут»; это НЕ то же, что mentioned=NO
-- (явное «не требуется») или UNKNOWN (не удалось разобрать текст).

ALTER TABLE job_posting
    ADD COLUMN raw_description TEXT;   -- текст описания, снятый из HTML источника

CREATE TABLE posting_language (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_posting_id     BIGINT NOT NULL,
    language_code      TEXT   NOT NULL,          -- ISO 639-1 (en, de, ...)
    mentioned          TEXT   NOT NULL,          -- LanguageMention: YES|NO|UNKNOWN
    modality           TEXT   NOT NULL,          -- LanguageModality: REQUIRED|PREFERRED|UNSPECIFIED
    source_fragment    TEXT,                     -- предложение-подтверждение, либо NULL
    extraction_version TEXT   NOT NULL,          -- версия правил извлечения
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_posting_language_posting FOREIGN KEY (job_posting_id) REFERENCES job_posting (id),
    CONSTRAINT uq_posting_language_posting_code UNIQUE (job_posting_id, language_code)
);
CREATE INDEX idx_posting_language_posting ON posting_language (job_posting_id);
