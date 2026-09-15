-- V5 — планирование обхода источников (§6 техдока, ADR-12).
--
-- Планировщик в job-worker под leader-lock создаёт для каждого активного
-- источника обход (crawl_run) на текущее окно расписания и задание (crawl_task),
-- вместе с outbox-событием, в одной транзакции. Идемпотентность планирования
-- обеспечивает уникальный ключ (source_id, window_start): повторный тик в том же
-- окне не создаёт второй обход.

CREATE TABLE crawl_run (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id    BIGINT      NOT NULL,
    -- Начало окна расписания (UTC), к которому относится обход.
    window_start TIMESTAMPTZ NOT NULL,
    state        TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_crawl_run_source
        FOREIGN KEY (source_id) REFERENCES source (id),
    -- Один обход на источник в окне — durable-гарантия идемпотентного планирования.
    CONSTRAINT uq_crawl_run_source_window
        UNIQUE (source_id, window_start)
);

CREATE INDEX idx_crawl_run_source ON crawl_run (source_id);

CREATE TABLE crawl_task (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    crawl_run_id BIGINT      NOT NULL,
    type         TEXT        NOT NULL,
    state        TEXT        NOT NULL,
    attempts     INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_crawl_task_run
        FOREIGN KEY (crawl_run_id) REFERENCES crawl_run (id)
);

CREATE INDEX idx_crawl_task_run ON crawl_task (crawl_run_id);
