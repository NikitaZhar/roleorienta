-- V18 — очередь кандидатов в работодатели (§5, ADR-6, §33).
-- Заводится слоем обнаружения (DISCOVER_EMPLOYER); подтверждается/отклоняется
-- администратором. Пара (provider_code, slug) уникальна — дедуп кандидата (§5).
-- Стиль как в V3: TEXT для строк/перечислений, TIMESTAMPTZ для времени.

CREATE TABLE employer_candidate (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_code TEXT        NOT NULL,
    slug          TEXT        NOT NULL,
    base_url      TEXT        NOT NULL,
    state         TEXT        NOT NULL,          -- EmployerCandidateState: PENDING | CONFIRMED | REJECTED
    confidence    TEXT        NOT NULL,          -- DiscoveryConfidence: HIGH | LOW | NONE
    reason        TEXT,
    posting_count INTEGER     NOT NULL DEFAULT 0,
    company_id    BIGINT,                         -- заполняется при подтверждении
    source_id     BIGINT,                         -- заполняется при подтверждении
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_employer_candidate_provider_slug UNIQUE (provider_code, slug),
    CONSTRAINT fk_employer_candidate_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_employer_candidate_source  FOREIGN KEY (source_id)  REFERENCES source (id)
);

-- Очередь читается по состоянию (PENDING в первую очередь).
CREATE INDEX idx_employer_candidate_state ON employer_candidate (state);
