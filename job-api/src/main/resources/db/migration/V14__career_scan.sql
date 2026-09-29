-- Проверка сайта компании на кадровые страницы (технический документ §5.1; подэтап 1.7).
--   company_site.checked_at   — когда сайт проверен; перепроверка — через срок (§17);
--   company_site.check_result — итог с причиной (бизнес-описание §4.2): SOURCE_FOUND — источник
--       подключён; NO_CAREER_PAGE — кадровая страница не найдена; FORMAT_UNSUPPORTED — страница
--       есть, формат не поддерживается; UNREACHABLE — сайт не ответил.
ALTER TABLE company_site
    ADD COLUMN checked_at   TIMESTAMPTZ,
    ADD COLUMN check_result VARCHAR(30)
        CHECK (check_result IN ('SOURCE_FOUND', 'NO_CAREER_PAGE', 'FORMAT_UNSUPPORTED', 'UNREACHABLE'));

-- Связь компании с источником (технический документ §4 CompanyCareerPage): доска или кадровая
-- страница, на которую ссылается подтверждённый сайт компании (гейт принадлежности, бизнес-описание
-- §4.2). Многие-ко-многим: одна доска может обслуживать несколько компаний группы.
CREATE TABLE company_source (
    company_id BIGINT      NOT NULL REFERENCES company (id),
    source_id  BIGINT      NOT NULL REFERENCES source (id),
    found_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, source_id)
);
