-- V22 — отклики пользователя на вакансии и заметки к ним (§7). Часть пути кандидата.
-- Персональные записи: проверяются по владельцу (A23). Пара (app_user_id, job_posting_id)
-- уникальна — повторный отклик на ту же публикацию не создаёт дубля (§7.8).

CREATE TABLE application (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_user_id    BIGINT      NOT NULL,
    job_posting_id BIGINT      NOT NULL,
    status         TEXT        NOT NULL,          -- ApplicationStatus: APPLIED | INTERVIEWING | OFFER | REJECTED | WITHDRAWN
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_application_user    FOREIGN KEY (app_user_id)    REFERENCES app_user (id),
    CONSTRAINT fk_application_posting FOREIGN KEY (job_posting_id) REFERENCES job_posting (id),
    CONSTRAINT uq_application_user_posting UNIQUE (app_user_id, job_posting_id)
);
CREATE INDEX idx_application_user ON application (app_user_id);

CREATE TABLE application_note (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT      NOT NULL,
    body           TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_application_note_application FOREIGN KEY (application_id) REFERENCES application (id)
);
CREATE INDEX idx_application_note_application ON application_note (application_id);
