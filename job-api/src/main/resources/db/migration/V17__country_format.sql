-- Страна и формат работы вакансии (бизнес-описание §3, §4.4; технический документ §6, §7;
-- подэтап 1.4).
--
-- geo_city — города GeoNames (cities15000, лицензия CC BY 4.0): нормализованное название → страна,
-- население (при одинаковом названии в разных странах берётся самый населённый). Загружается
-- приложением (задание GEO_IMPORT).
CREATE TABLE geo_city (
    name       VARCHAR(200) NOT NULL,
    country    CHAR(2)      NOT NULL,
    population BIGINT       NOT NULL,
    PRIMARY KEY (name, country)
);

-- Сведения вакансии для оценки страны и формата — предрасчитываются вместе с соответствием позициям:
--   work_countries    — страны выполнения работы (ISO 3166-1 alpha-2) из мест публикаций и
--                       территорий удалённой работы; '*' — территория без ограничения (worldwide);
--   country_uncertain — есть место без ясной страны: «удалённо» без территории, неизвестное место;
--   work_format       — OFFICE / HYBRID / REMOTE; CONFLICT — источники или текст противоречат друг
--                       другу; NULL — не указан.
ALTER TABLE vacancy
    ADD COLUMN work_countries    TEXT[],
    ADD COLUMN country_uncertain BOOLEAN,
    ADD COLUMN work_format       VARCHAR(10) CHECK (work_format IN ('OFFICE', 'HYBRID', 'REMOTE', 'CONFLICT'));
