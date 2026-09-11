-- V3 — модель данных, срез 1: источники и публикации (§4, ADR-16).
-- Provider != Source != Company; публикация уникальна в паре (источник, внешний ID).
-- Типы строк — TEXT (Postgres), перечисления хранятся строками, время — TIMESTAMPTZ.

CREATE TABLE provider (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         TEXT        NOT NULL,
    display_name TEXT        NOT NULL,
    kind         TEXT        NOT NULL,          -- ProviderKind: ATS | BOARD
    capabilities JSONB,                          -- capability-карточка провайдера
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_code UNIQUE (code)
);

CREATE TABLE company (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           TEXT        NOT NULL,
    primary_domain TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE source (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_id     BIGINT      NOT NULL,
    kind            TEXT        NOT NULL,        -- SourceKind: COMPANY_BOARD | PLATFORM_AREA
    external_ref    TEXT        NOT NULL,
    base_url        TEXT        NOT NULL,
    schedule        TEXT,
    state           TEXT        NOT NULL,        -- SourceState: ACTIVE | PAUSED | DISABLED
    adapter_version TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_source_provider FOREIGN KEY (provider_id) REFERENCES provider (id),
    CONSTRAINT uq_source_provider_external_ref UNIQUE (provider_id, external_ref)
);
CREATE INDEX idx_source_provider_id ON source (provider_id);

CREATE TABLE company_source (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id   BIGINT      NOT NULL,
    company_id  BIGINT      NOT NULL,
    verified_by TEXT        NOT NULL,            -- VerifiedBy: AUTO | MANUAL
    verified_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_company_source_source  FOREIGN KEY (source_id)  REFERENCES source (id),
    CONSTRAINT fk_company_source_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT uq_company_source_source_company UNIQUE (source_id, company_id)
);
CREATE INDEX idx_company_source_company_id ON company_source (company_id);

CREATE TABLE job_posting (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id     BIGINT      NOT NULL,
    external_id   TEXT        NOT NULL,
    url           TEXT        NOT NULL,
    raw_title     TEXT        NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_job_posting_source FOREIGN KEY (source_id) REFERENCES source (id),
    CONSTRAINT uq_job_posting_source_external_id UNIQUE (source_id, external_id)
);
