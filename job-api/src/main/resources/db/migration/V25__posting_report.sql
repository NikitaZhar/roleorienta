-- V25 — жалобы пользователей на публикации (§46): «сообщить об ошибке». Битая ссылка, дубль,
-- устаревшая вакансия и т.п. Персональные записи (A23): доступны только автору жалобы. Статус —
-- очередь модерации (OPEN по умолчанию; RESOLVED/DISMISSED — для будущего разбора). Пара
-- (app_user_id, job_posting_id) уникальна — повторная жалоба того же автора не создаёт дубля.

CREATE TABLE posting_report (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_user_id    BIGINT      NOT NULL,
    job_posting_id BIGINT      NOT NULL,
    reason         TEXT        NOT NULL,          -- PostingReportReason
    comment        TEXT,
    status         TEXT        NOT NULL,          -- PostingReportStatus: OPEN | RESOLVED | DISMISSED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_posting_report_user    FOREIGN KEY (app_user_id)    REFERENCES app_user (id),
    CONSTRAINT fk_posting_report_posting FOREIGN KEY (job_posting_id) REFERENCES job_posting (id),
    CONSTRAINT uq_posting_report_user_posting UNIQUE (app_user_id, job_posting_id)
);
CREATE INDEX idx_posting_report_posting ON posting_report (job_posting_id);
