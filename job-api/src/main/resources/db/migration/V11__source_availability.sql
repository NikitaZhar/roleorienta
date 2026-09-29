-- Доступность источника (технический документ §4 Source, §9; бизнес-описание §4.6):
--   OK           — последнее чтение получило ответ списка (полное или неполное);
--   TEMP_FAILING — временные отказы (таймаут, 5xx, 429) с failing_since;
--   UNAVAILABLE  — временные отказы дольше срока (§17, 7 дней) или постоянный отказ (доступ
--                  запрещён, страница удалена, запрет robots.txt): кадровая страница требует
--                  перепроверки (подэтап 1.7).
ALTER TABLE source
    ADD COLUMN availability  VARCHAR(20) NOT NULL DEFAULT 'OK'
                             CHECK (availability IN ('OK', 'TEMP_FAILING', 'UNAVAILABLE')),
    ADD COLUMN failing_since TIMESTAMPTZ;
