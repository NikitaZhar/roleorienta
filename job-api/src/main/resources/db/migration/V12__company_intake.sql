-- Общий сбор компаний (технический документ §4 Company, IntakeCursor; §5.1; подэтап 1.6).
--
-- company — организация из реестра юрлиц страны: регистрационный номер (IČO в Словакии) уникален
-- в пространстве страны (CompanyIdentity для реестра). Берутся только юрлица, включая органы
-- власти и города; предприниматели-физлица не берутся (решение владельца, §24). terminated_on —
-- дата прекращения по реестру: такая компания больше не обрабатывается, запись сохраняется.
-- registry — атрибуция реестра (RPO — лицензия CC BY 4.0).
CREATE TABLE company (
    id                  BIGSERIAL    PRIMARY KEY,
    country             CHAR(2)      NOT NULL,
    registration_number VARCHAR(20)  NOT NULL,
    name                VARCHAR(500) NOT NULL,
    legal_form          VARCHAR(200),
    municipality        VARCHAR(200),
    registry            VARCHAR(20)  NOT NULL,
    terminated_on       DATE,
    first_seen_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (country, registration_number)
);

-- Место обработки реестра страны. Сначала читается полная выгрузка export_date (файл file_index,
-- обработано record_offset записей), затем ежедневные выгрузки: daily_date — последняя применённая
-- (NULL — полная выгрузка ещё читается). version растёт с каждым сдвигом: сдвиг выполняется, только
-- если версия не изменилась, поэтому два задания не могут вести курсор одновременно.
CREATE TABLE intake_cursor (
    country       CHAR(2)     PRIMARY KEY,
    registry      VARCHAR(20) NOT NULL,
    export_date   DATE        NOT NULL,
    file_index    INTEGER     NOT NULL,
    record_offset BIGINT      NOT NULL,
    daily_date    DATE,
    version       BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
