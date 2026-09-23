-- V26 — состояние автоматического входа обнаружения (§55, A1b): курсор обхода внешнего
-- индекса и накопитель найденных досок. Владелец данных — job-worker (CcHarvestScheduler),
-- схема — job-api (ADR-2). Стиль как в V3/V18: TEXT для строк/перечислений, TIMESTAMPTZ.

-- Курсор одного входа (напр. 'cc-workday' — индекс Common Crawl по *.myworkdayjobs.com).
-- collection/page_size/page_count/next_page — докуда обойдена текущая коллекция индекса;
-- номера страниц зависят от page_size, поэтому новая коллекция или другой page_size
-- начинают обход с page 0. lease_until — аренда прохода: сетевая часть идёт вне
-- транзакции, аренда не даёт двум репликам обходить одно и то же одновременно.
CREATE TABLE harvest_cursor (
    input_code  TEXT        PRIMARY KEY,
    collection  TEXT,
    page_size   INTEGER     NOT NULL DEFAULT 0,
    page_count  INTEGER     NOT NULL DEFAULT 0,
    next_page   INTEGER     NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Найденные доски (provider + slug), ещё не обязательно переданные в DISCOVER_EMPLOYER.
-- Развязывает чтение индекса (страница даёт сотни досок) и fan-out (бюджет A29 за проход):
-- ничего не теряется и не ставится дважды. dedup_key — slug в нижнем регистре (Workday cxs
-- к регистру site не чувствителен, §53). state: NEW → ENQUEUED (задание поставлено) |
-- SKIPPED (кандидат уже есть в employer_candidate).
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

-- Fan-out читает NEW по порядку обнаружения.
CREATE INDEX idx_harvested_board_new ON harvested_board (id) WHERE state = 'NEW';

INSERT INTO harvest_cursor (input_code) VALUES ('cc-workday');
