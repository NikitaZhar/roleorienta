-- Outbox ставит в очередь только задания: тип и тело события не используются, потребитель читает
-- id задания из заголовка сообщения. Событие без задания недопустимо.
DELETE FROM outbox_event WHERE task_id IS NULL;
ALTER TABLE outbox_event DROP COLUMN event_type, DROP COLUMN payload;
ALTER TABLE outbox_event ALTER COLUMN task_id SET NOT NULL;
