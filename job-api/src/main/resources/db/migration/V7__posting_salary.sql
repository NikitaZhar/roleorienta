-- V7 — нормализованная зарплата публикации (§6, A09).
-- Сырое значение (raw_compensation, V6) остаётся рядом; здесь — структура.
-- Nullable-колонки (шаг expand, ADR-2). Правило A09: валюта, период и база
-- (gross/net) не смешиваются, неизвестное помечается явно (UNKNOWN в period/basis
-- при наличии суммы; NULL во всех колонках — зарплата не указана вовсе).

ALTER TABLE job_posting
    ADD COLUMN salary_min      NUMERIC,  -- нижняя граница суммы
    ADD COLUMN salary_max      NUMERIC,  -- верхняя граница суммы
    ADD COLUMN salary_currency TEXT,     -- код валюты (напр. EUR)
    ADD COLUMN salary_period   TEXT,     -- SalaryPeriod: YEAR|MONTH|WEEK|DAY|HOUR|UNKNOWN
    ADD COLUMN salary_basis    TEXT;     -- SalaryBasis: GROSS|NET|UNKNOWN
