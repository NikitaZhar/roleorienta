-- Общий список вакансий государственного портала (технический документ §15, п. 2, срез 4; стенограмма §104, §105).
--
-- portal_cycle — полные обходы общего списка (/search/ponuky): начало и конец. Обход, не дошедший до конца,
-- остаётся незавершённым и в итог не идёт; следующий начинается заново с первой страницы.
--
-- portal_offer — вакансии общего списка. external_id — как у публикации источника портала (job_posting):
-- uuid своей вакансии портала, «profesia:<номер>» или адрес объявления другой площадки. registration_number —
-- IČO работодателя (в списке его нет; определяется по названию или со страницы вакансии), NULL — не определён.
-- last_seen_cycle — последний полный обход, в котором вакансия была в списке; лента «новые сверху» его не меняет.
--
-- portal_employer_name — IČO по названию работодателя из поиска работодателей портала
-- (/enum/vyhladavanie/zamestnavatelia): одно название — один запрос; registration_number NULL — портал не дал
-- однозначного ответа, название ищется снова через срок (checked_at).
CREATE TABLE portal_cycle (
    id           BIGSERIAL    PRIMARY KEY,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE portal_offer (
    external_id         VARCHAR(2000) PRIMARY KEY,
    employer_name       VARCHAR(500)  NOT NULL,
    registration_number VARCHAR(20),
    title               VARCHAR(500)  NOT NULL,
    place               VARCHAR(500),
    url                 VARCHAR(2000) NOT NULL,
    first_seen_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_seen_cycle     BIGINT        REFERENCES portal_cycle (id)
);

CREATE INDEX portal_offer_number_idx ON portal_offer (registration_number);

CREATE TABLE portal_employer_name (
    name                VARCHAR(500) PRIMARY KEY,
    registration_number VARCHAR(20),
    checked_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
