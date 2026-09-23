-- Тестовая фикстура схемы для интеграционного теста HarvestStore (§55).
-- ИСТОЧНИК ИСТИНЫ — миграция job-api V26__discovery_harvest.sql. job-worker не применяет
-- миграции (ADR-2), Testcontainers поднимает пустую БД. При изменении V26 синхронизировать.
-- DROP + CREATE делают состояние каждого теста детерминированным.

DROP TABLE IF EXISTS harvested_board;
DROP TABLE IF EXISTS harvest_cursor;

CREATE TABLE harvest_cursor (
    input_code  TEXT        PRIMARY KEY,
    collection  TEXT,
    page_size   INTEGER     NOT NULL DEFAULT 0,
    page_count  INTEGER     NOT NULL DEFAULT 0,
    next_page   INTEGER     NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE harvested_board (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    input_code    TEXT        NOT NULL,
    provider_code TEXT        NOT NULL,
    slug          TEXT        NOT NULL,
    dedup_key     TEXT        NOT NULL,
    base_url      TEXT        NOT NULL,
    state         TEXT        NOT NULL DEFAULT 'NEW',
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    handled_at    TIMESTAMPTZ,
    CONSTRAINT uq_harvested_board_provider_key UNIQUE (provider_code, dedup_key),
    CONSTRAINT ck_harvested_board_state CHECK (state IN ('NEW', 'ENQUEUED', 'SKIPPED'))
);

CREATE INDEX idx_harvested_board_new ON harvested_board (id) WHERE state = 'NEW';
