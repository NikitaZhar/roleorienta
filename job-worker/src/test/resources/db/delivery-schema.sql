-- Тестовая фикстура схемы для интеграционных тестов job-worker.
-- ИСТОЧНИК ИСТИНЫ — миграции job-api (outbox_event: V2; processed_message: V4).
-- Здесь схема повторена, потому что job-worker не применяет миграции (ADR-2), а
-- Testcontainers поднимает пустую БД. При изменении V2/V4 синхронизировать этот файл.
-- DROP + CREATE делают состояние каждого теста детерминированным.

DROP TABLE IF EXISTS outbox_event;
DROP TABLE IF EXISTS processed_message;

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

CREATE INDEX idx_outbox_event_unpublished
    ON outbox_event (id)
    WHERE published_at IS NULL;

CREATE TABLE processed_message (
    idempotency_key TEXT        PRIMARY KEY,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
