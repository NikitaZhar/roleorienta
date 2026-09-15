-- Тестовая фикстура схемы для интеграционных тестов планировщика job-worker.
-- ИСТОЧНИК ИСТИНЫ — миграции job-api (provider/source: V3; crawl_run/crawl_task: V5;
-- outbox_event: V2; processed_message: V4). job-worker не применяет миграции (ADR-2),
-- а Testcontainers поднимает пустую БД, поэтому нужные таблицы создаются здесь.
-- При изменении миграций синхронизировать этот файл. DROP + CREATE делают состояние
-- каждого теста детерминированным.

DROP TABLE IF EXISTS crawl_task;
DROP TABLE IF EXISTS crawl_run;
DROP TABLE IF EXISTS source;
DROP TABLE IF EXISTS provider;
DROP TABLE IF EXISTS outbox_event;
DROP TABLE IF EXISTS processed_message;

CREATE TABLE provider (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         TEXT        NOT NULL,
    display_name TEXT        NOT NULL,
    kind         TEXT        NOT NULL,
    capabilities JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_code UNIQUE (code)
);

CREATE TABLE source (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_id     BIGINT      NOT NULL,
    kind            TEXT        NOT NULL,
    external_ref    TEXT        NOT NULL,
    base_url        TEXT        NOT NULL,
    schedule        TEXT,
    state           TEXT        NOT NULL,
    adapter_version TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_source_provider FOREIGN KEY (provider_id) REFERENCES provider (id),
    CONSTRAINT uq_source_provider_external_ref UNIQUE (provider_id, external_ref)
);
CREATE INDEX idx_source_provider_id ON source (provider_id);

CREATE TABLE crawl_run (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id    BIGINT      NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    state        TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_crawl_run_source FOREIGN KEY (source_id) REFERENCES source (id),
    CONSTRAINT uq_crawl_run_source_window UNIQUE (source_id, window_start)
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
    CONSTRAINT fk_crawl_task_run FOREIGN KEY (crawl_run_id) REFERENCES crawl_run (id)
);
CREATE INDEX idx_crawl_task_run ON crawl_task (crawl_run_id);

CREATE TABLE outbox_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    headers        JSONB,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER     NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbox_event_unpublished ON outbox_event (id) WHERE published_at IS NULL;

CREATE TABLE processed_message (
    idempotency_key TEXT        PRIMARY KEY,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
