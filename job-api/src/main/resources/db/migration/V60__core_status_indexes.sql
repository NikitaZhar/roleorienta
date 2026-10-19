-- Состояние ядра для администратора (аудит §143): последнее чтение и чтения за час — по концу чтения;
-- задания в очереди и неудачные — по времени смены состояния, без прохода по всей таблице task.
CREATE INDEX crawl_run_finished_idx ON crawl_run (finished_at);
CREATE INDEX task_queued_idx ON task (updated_at) WHERE state = 'QUEUED';
CREATE INDEX task_failed_idx ON task (updated_at) WHERE state = 'FAILED';
