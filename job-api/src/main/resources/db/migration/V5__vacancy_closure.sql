-- Подтверждение и закрытие (бизнес-описание §4.3):
--   confirmed              — публикацию подтвердило последнее чтение её источника;
--   missing_complete_reads — сколько полных чтений подряд публикации не было;
--   closed_at              — публикация / вакансия закрыта (история сохраняется).
ALTER TABLE job_posting
    ADD COLUMN confirmed              BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN missing_complete_reads INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN closed_at              TIMESTAMPTZ;

ALTER TABLE vacancy ADD COLUMN closed_at TIMESTAMPTZ;
