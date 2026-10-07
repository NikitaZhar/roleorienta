-- Эталонный список компаний для отработки ядра (стенограмма §69; план стабилизации ядра, шаг 2).
-- Отдельная схема benchmark: не часть приложения, Flyway и Hibernate её не трогают. Повторный запуск пересоздаёт
-- таблицы списка (данные загружаются заново из CSV); другие таблицы схемы не затрагиваются.
CREATE SCHEMA IF NOT EXISTS benchmark;

DROP VIEW IF EXISTS benchmark.company;
DROP TABLE IF EXISTS benchmark.foreign_top500;

-- Иностранные компании Словакии, 500 крупнейших по обороту за полные финансовые годы, завершившиеся в 2024
-- (файл владельца 2026-10-07: RÚZ и SlovakData, код собственности RÚZ 7 или 8).
CREATE TABLE benchmark.foreign_top500 (
    rank                INTEGER      PRIMARY KEY,
    registration_number VARCHAR(20)  NOT NULL UNIQUE,
    name                VARCHAR(500) NOT NULL,
    city                VARCHAR(200),
    turnover_eur        BIGINT       NOT NULL,
    period              VARCHAR(40)  NOT NULL,
    ownership_code      CHAR(1)      NOT NULL
);

-- Эталон с компанией реестра ядра: company_id — NULL, если в реестре ядра её нет.
CREATE VIEW benchmark.company AS
SELECT f.registration_number, f.name, f.rank, c.id AS company_id
FROM benchmark.foreign_top500 f
LEFT JOIN public.company c ON c.country = 'SK' AND c.registration_number = f.registration_number;
