-- V8 — история изменений публикации (§6, A06/A16): «было → стало» по полям.
-- Каждая существенная смена поля между сборами — отдельная строка. Первичное
-- заполнение изменением не считается (пишется только реальная смена значения).

CREATE TABLE posting_revision (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_posting_id BIGINT      NOT NULL,
    field_name     TEXT        NOT NULL,          -- какое поле изменилось (напр. salary_min)
    old_value      TEXT,                          -- было
    new_value      TEXT,                          -- стало
    detected_at    TIMESTAMPTZ NOT NULL,          -- когда изменение обнаружено
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_posting_revision_posting FOREIGN KEY (job_posting_id) REFERENCES job_posting (id)
);
CREATE INDEX idx_posting_revision_posting ON posting_revision (job_posting_id);
