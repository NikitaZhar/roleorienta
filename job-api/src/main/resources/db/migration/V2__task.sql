-- Фоновые задания (технический документ §4 Task, §6, §9): состояние, попытки, срок повтора и
-- аренда выполнения хранятся в БД, поэтому переживают перезапуск приложения и брокера.
--   QUEUED  — поставлено в очередь (событие в outbox);
--   RUNNING — выполняется, аренда до lease_until; истёкшая аренда — задание снова в очередь;
--   WAITING — временный отказ, повтор не раньше next_attempt_at;
--   DONE / FAILED — завершено успешно / окончательно.
CREATE TABLE task (
    id              BIGSERIAL     PRIMARY KEY,
    type            VARCHAR(50)   NOT NULL,
    task_key        VARCHAR(200)  NOT NULL UNIQUE,
    payload         JSONB         NOT NULL,
    state           VARCHAR(20)   NOT NULL
                    CHECK (state IN ('QUEUED', 'RUNNING', 'WAITING', 'DONE', 'FAILED')),
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    lease_until     TIMESTAMPTZ,
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Частичные индексы для перепостановки: задания, ждущие повтора, и задания с арендой.
CREATE INDEX task_waiting_idx ON task (next_attempt_at) WHERE state = 'WAITING';
CREATE INDEX task_running_idx ON task (lease_until) WHERE state = 'RUNNING';

-- Событие outbox, ставящее задание в очередь, ссылается на задание; id задания уходит в заголовок
-- сообщения.
ALTER TABLE outbox_event ADD COLUMN task_id BIGINT REFERENCES task (id);
