-- V6 — детальные поля публикации (§6, задание FETCH_POSTING).
-- Добавляются nullable-колонки (шаг expand схемы expand→migrate→contract, ADR-2):
-- существующие строки и вставка в DISCOVER_PAGE не затрагиваются, значения
-- проставляет обработчик FETCH_POSTING. Значения хранятся сырыми; нормализация
-- (валюта/период/gross-net, §6, A09) — отдельный срез.

ALTER TABLE job_posting
    ADD COLUMN raw_location      TEXT,        -- сырая локация с детальной страницы
    ADD COLUMN raw_compensation  TEXT,        -- сырая строка зарплаты/компенсации
    ADD COLUMN detail_fetched_at TIMESTAMPTZ; -- когда деталь дозапрошена (FETCH_POSTING)
