-- Аудит §99–§108 (стенограмма §109).
-- 1) Страница общего списка, которую портал стабильно не отдаёт, после нескольких попыток пропускается, чтобы обход
--    не останавливался навсегда; обход с пропущенной страницей — неполный: его записи в источники — неполные чтения
--    (отсутствие не засчитывается), закрытия по нему нет.
ALTER TABLE portal_cycle ADD COLUMN skipped_pages INTEGER NOT NULL DEFAULT 0;

-- 2) Номер обхода у вакансии не читался: полнота записи источника считается по last_seen_at (начало обхода).
ALTER TABLE portal_offer DROP COLUMN last_seen_cycle;

-- 3) Параметры заданий записи источника портала (PORTAL_RECORD): «complete» (true/false) заменён на «partial» (причина
--    неполного чтения, null — полное). Незавершённые задания прежней версии переводятся: иначе неполная запись ленты
--    выполнилась бы как полная и засчитала отсутствие вакансий.
UPDATE task
SET payload = (payload - 'complete') || jsonb_build_object('partial',
        CASE WHEN (payload ->> 'complete')::boolean THEN NULL ELSE 'NEW_OFFERS' END)
WHERE type = 'PORTAL_RECORD' AND state IN ('QUEUED', 'RUNNING', 'WAITING') AND payload ? 'complete';

-- 4) IČO по названию из списка работодателей портала ищется на каждую вакансию без IČO (первый обход — ~25 тыс.).
CREATE INDEX portal_employer_name_idx ON portal_employer (name);
