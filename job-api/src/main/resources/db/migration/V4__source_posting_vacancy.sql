-- Источник, публикация и вакансия (технический документ §4).
-- Источник — подключённая кадровая страница; связь с компанией и кадровой страницей добавится с
-- обнаружением (подэтап 1.7).
CREATE TABLE source (
    id         BIGSERIAL    PRIMARY KEY,
    provider   VARCHAR(30)  NOT NULL,
    board      VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider, board)
);

-- Вакансия объединяет одну или несколько публикаций (бизнес-описание §4.3).
CREATE TABLE vacancy (
    id                BIGSERIAL     PRIMARY KEY,
    state             VARCHAR(20)   NOT NULL CHECK (state IN ('ACTIVE', 'NEEDS_RECHECK', 'CLOSED')),
    title             VARCHAR(500)  NOT NULL,
    primary_url       VARCHAR(2000) NOT NULL,
    first_seen_at     TIMESTAMPTZ   NOT NULL,
    last_confirmed_at TIMESTAMPTZ   NOT NULL
);

-- Публикация — запись вакансии в одном источнике; уникальна в паре «источник + внешний id».
CREATE TABLE job_posting (
    id                BIGSERIAL     PRIMARY KEY,
    source_id         BIGINT        NOT NULL REFERENCES source (id),
    vacancy_id        BIGINT        NOT NULL REFERENCES vacancy (id),
    external_id       VARCHAR(200)  NOT NULL,
    title             VARCHAR(500)  NOT NULL,
    url               VARCHAR(2000) NOT NULL,
    location          VARCHAR(500),
    content           TEXT,
    first_seen_at     TIMESTAMPTZ   NOT NULL,
    last_confirmed_at TIMESTAMPTZ   NOT NULL,
    UNIQUE (source_id, external_id)
);

CREATE INDEX job_posting_vacancy_idx ON job_posting (vacancy_id);
