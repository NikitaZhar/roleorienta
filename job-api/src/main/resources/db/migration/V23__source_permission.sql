-- Основание использования источников и кадровые агентства (бизнес-описание §4.2, §10; технический
-- документ §4 SourcePermission, CompanyCareerPage; §5.1; стенограмма §40).

-- Реестр оснований: источник подключается, только если для его провайдера есть разрешение; доска
-- кадрового агентства — ещё и с разрешением на это агентство. Нет записи — использование запрещено.
--   scope    — PROVIDER (provider: код провайдера) или AGENCY (company_id: агентство);
--   decision — ALLOW / DENY; basis — основание словами; checked_on — дата проверки основания.
CREATE TABLE source_permission (
    id         BIGSERIAL    PRIMARY KEY,
    scope      VARCHAR(10)  NOT NULL CHECK (scope IN ('PROVIDER', 'AGENCY')),
    provider   VARCHAR(30),
    company_id BIGINT       REFERENCES company (id) ON DELETE CASCADE,
    decision   VARCHAR(10)  NOT NULL CHECK (decision IN ('ALLOW', 'DENY')),
    basis      VARCHAR(1000) NOT NULL,
    checked_on DATE         NOT NULL,
    CHECK ((scope = 'PROVIDER') = (provider IS NOT NULL AND company_id IS NULL)),
    CHECK ((scope = 'AGENCY') = (company_id IS NOT NULL AND provider IS NULL)),
    UNIQUE (scope, provider),
    UNIQUE (scope, company_id)
);

INSERT INTO source_permission (scope, provider, decision, basis, checked_on) VALUES
('PROVIDER', 'workday', 'ALLOW', 'Публичная витрина вакансий работодателя; robots.txt соблюдается; текст служит отбору и не переиздаётся (технический документ §5, §10)', DATE '2026-10-01'),
('PROVIDER', 'greenhouse', 'ALLOW', 'Публичный API досок вакансий (boards-api); robots.txt соблюдается; текст служит отбору и не переиздаётся (технический документ §5, §10)', DATE '2026-10-01'),
('PROVIDER', 'personio', 'ALLOW', 'Публичная XML-лента вакансий компании; robots.txt соблюдается; текст служит отбору и не переиздаётся (технический документ §5, §10)', DATE '2026-10-01'),
('PROVIDER', 'smartrecruiters', 'ALLOW', 'Решение владельца (стенограмма §35): страницы, открытые соискателю, только вакансии страны сбора, текст один раз на вакансию и только для отбора; API не используется (запрещён robots.txt)', DATE '2026-09-30'),
('PROVIDER', 'jobposting', 'ALLOW', 'Общее правило для сайтов компаний: публичная разметка schema.org JobPosting при разрешении robots.txt (технический документ §5.1)', DATE '2026-10-01');

-- Признак кадрового агентства: основной вид деятельности по реестру SK NACE 78 (агентства занятости).
-- Заполняется приёмом реестра; у уже загруженных юрлиц — повторным чтением полной выгрузки (ниже).
ALTER TABLE company ADD COLUMN agency BOOLEAN NOT NULL DEFAULT FALSE;

-- Роль компании у источника: работодатель или кадровое агентство (размещающая сторона).
ALTER TABLE company_source ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'EMPLOYER'
    CHECK (role IN ('EMPLOYER', 'AGENCY'));

-- Итог «использование запрещено»: нет основания для провайдера или агентства, запрет robots.txt.
ALTER TABLE company_site DROP CONSTRAINT company_site_check_result_check;
ALTER TABLE company_site ADD CONSTRAINT company_site_check_result_check
    CHECK (check_result IN ('SOURCE_FOUND', 'NO_CAREER_PAGE', 'FORMAT_UNSUPPORTED', 'UNREACHABLE', 'USE_FORBIDDEN'));
ALTER TABLE company_check DROP CONSTRAINT company_check_result_check;
ALTER TABLE company_check ADD CONSTRAINT company_check_result_check
    CHECK (result IN ('CONNECTED', 'SITE_NOT_FOUND', 'PAGE_NOT_FOUND', 'FORMAT_UNSUPPORTED', 'SOURCE_UNAVAILABLE',
                      'USE_FORBIDDEN'));

-- Повторное чтение полной выгрузки RPO с начала, чтобы у уже загруженных юрлиц появился признак
-- агентства; дальше приём сам переходит к ежедневным выгрузкам. Версия курсора растёт — ключ
-- следующего задания приёма новый.
UPDATE intake_cursor SET file_index = 0, record_offset = 0, daily_date = NULL, version = version + 1,
                         updated_at = now();
