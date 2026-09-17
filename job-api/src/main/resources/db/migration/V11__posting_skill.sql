-- V11 — структурированные требования: технологии/навыки из описания (§6, A08).
-- Шаг expand (ADR-2): отдельная таблица (у навыка своя модель, отличная от языка A07).
--
-- Навык хранится в каноническом виде (skill): алиасы источника (Postgres/PostgreSQL)
-- сводятся к одному навыку при извлечении, поэтому строка уникальна в паре
-- (job_posting, skill). Обязательность (modality) — REQUIRED|PREFERRED|UNSPECIFIED;
-- при альтернативе («Java or Kotlin») и отрицании/миграции ставится UNSPECIFIED, а не
-- REQUIRED (обязательность конкретного навыка из текста не следует). Отсутствие строки
-- по навыку = «не упомянут». source_fragment хранит предложение-подтверждение;
-- extraction_version — версию правил (для воспроизводимости и пересчёта).

CREATE TABLE posting_skill (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_posting_id     BIGINT NOT NULL,
    skill              TEXT   NOT NULL,          -- каноническое имя навыка (Java, PostgreSQL, C#, ...)
    modality           TEXT   NOT NULL,          -- RequirementModality: REQUIRED|PREFERRED|UNSPECIFIED
    source_fragment    TEXT,                     -- предложение-подтверждение, либо NULL
    extraction_version TEXT   NOT NULL,          -- версия правил извлечения
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_posting_skill_posting FOREIGN KEY (job_posting_id) REFERENCES job_posting (id),
    CONSTRAINT uq_posting_skill_posting_skill UNIQUE (job_posting_id, skill)
);
CREATE INDEX idx_posting_skill_posting ON posting_skill (job_posting_id);
