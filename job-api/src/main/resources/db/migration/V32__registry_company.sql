-- V32 — компании региона из открытого реестра юрлиц (план R1, R7; §95, ADR-19). Основной путь
-- обнаружения «от региона и профиля»: реестр → сайт компании → карьерная страница → вакансии.
-- Строка — юрлицо реестра (Словакия — RPO, CC BY 4.0) с основным видом деятельности и итогом
-- последней проверки пути: сайт, подтверждённый номером юрлица на странице (IČO), карьерная
-- страница и её тип, число вакансий и вакансий ниши. Владелец данных — job-worker (region),
-- схема — job-api (ADR-2).
-- career_system: код системы найма (workday, personio, greenhouse, lever, teamtailor …),
-- 'schema-org' (своя страница с разметкой JobPosting), 'own-page' (своя страница без разметки),
-- 'none' (раздела вакансий не найдено); NULL — сайт не найден или проверки ещё не было.

CREATE TABLE registry_company (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    country            TEXT        NOT NULL,          -- ISO 3166-1 alpha-2: SK
    registry           TEXT        NOT NULL,          -- источник: RPO
    registry_id        TEXT        NOT NULL,          -- номер юрлица в реестре (IČO)
    name               TEXT        NOT NULL,
    municipality       TEXT,
    activity_code      TEXT,                          -- основной вид деятельности (SK NACE)
    activity           TEXT,
    website            TEXT,                          -- подтверждённый сайт компании
    career_url         TEXT,
    career_system      TEXT,
    posting_count      INTEGER,                       -- NULL — не считали (система без адаптера)
    niche_count        INTEGER,
    check_note         TEXT,                          -- чем кончилась проверка, для человека
    checked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_registry_company UNIQUE (country, registry, registry_id)
);

-- Очередь проверки: ещё не проверенные, по порядку импорта.
CREATE INDEX idx_registry_company_unchecked ON registry_company (id) WHERE checked_at IS NULL;
