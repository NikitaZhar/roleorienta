-- V16 — персональные маркеры публикации (§7): «сохранить» и «скрыть» (с причиной).
-- Одна строка на пару (пользователь, публикация); state — SavedState (SAVED|HIDDEN).
-- Шаг expand (ADR-2): только новая таблица, существующие не затрагиваются.
-- Соглашения как в остальной схеме: id — IDENTITY, строки — TEXT, время — TIMESTAMPTZ,
-- enum'ы хранятся строками (V3/V14).

CREATE TABLE saved_posting (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_user_id    BIGINT      NOT NULL,          -- владелец маркера (из сессии, не из запроса)
    job_posting_id BIGINT      NOT NULL,          -- помеченная публикация
    state          TEXT        NOT NULL,          -- SavedState: SAVED | HIDDEN
    hidden_reason  TEXT,                           -- причина; только для HIDDEN, иначе NULL
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_saved_posting_user
        FOREIGN KEY (app_user_id)    REFERENCES app_user (id),
    CONSTRAINT fk_saved_posting_posting
        FOREIGN KEY (job_posting_id) REFERENCES job_posting (id),
    -- Один маркер на пару (пользователь, публикация): durable-страховка идемпотентности
    -- «сохранить/скрыть» (повтор не создаёт вторую строку).
    CONSTRAINT uq_saved_posting_user_posting UNIQUE (app_user_id, job_posting_id)
);

-- Ускоряет выборку «мои сохранённые/скрытые» (лента маркеров по владельцу).
CREATE INDEX idx_saved_posting_user ON saved_posting (app_user_id);
