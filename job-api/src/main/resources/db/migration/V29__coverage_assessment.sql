-- V29 — оценка покрытия публикации площадками (A5, ADR-15, §81). Одна запись на публикацию;
-- нет записи — «не проверено» (UNKNOWN), поэтому заполнять таблицу для существующих публикаций
-- не нужно. Записи создаёт сравнение с эталонной площадкой (следующий срез).
-- Оценка — часть публикации: удаление публикации удаляет и её оценку (ON DELETE CASCADE).
-- state: UNKNOWN | SITE_ONLY | ON_PLATFORM | BOTH (CoverageState).

CREATE TABLE coverage_assessment (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_posting_id    BIGINT      NOT NULL,
    state             TEXT        NOT NULL,
    checked_platforms TEXT,
    reason            TEXT,
    checked_at        TIMESTAMPTZ,
    matcher_version   TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_coverage_assessment_posting FOREIGN KEY (job_posting_id)
        REFERENCES job_posting (id) ON DELETE CASCADE,
    CONSTRAINT uq_coverage_assessment_posting UNIQUE (job_posting_id),
    CONSTRAINT ck_coverage_assessment_state
        CHECK (state IN ('UNKNOWN', 'SITE_ONLY', 'ON_PLATFORM', 'BOTH'))
);

-- Фильтр ленты «только скрытые» (coverage=SITE_ONLY).
CREATE INDEX idx_coverage_assessment_state ON coverage_assessment (state);
