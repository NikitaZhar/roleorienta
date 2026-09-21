-- V24 — собеседования по отклику (§43). Часть пути кандидата после статуса INTERVIEWING.
-- Персональные записи: доступны только через владельца отклика (A23). Время хранится в UTC
-- (scheduled_at), пользовательская таймзона (zone_id, IANA) — рядом, для отображения и переноса.

CREATE TABLE interview (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT      NOT NULL,
    scheduled_at   TIMESTAMPTZ NOT NULL,
    zone_id        TEXT        NOT NULL,          -- IANA zone id, напр. Europe/Amsterdam
    status         TEXT        NOT NULL,          -- InterviewStatus: SCHEDULED | CANCELLED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_interview_application FOREIGN KEY (application_id) REFERENCES application (id)
);
CREATE INDEX idx_interview_application ON interview (application_id);
