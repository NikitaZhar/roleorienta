-- V2 — таблица transactional outbox (инфраструктура доставки, ADR-1/ADR-12).
--
-- События записываются в эту таблицу в той же транзакции БД, что и изменение
-- данных; публикатор в job-worker читает неопубликованные строки через
-- SELECT ... FOR UPDATE SKIP LOCKED и отправляет их в RabbitMQ с publisher
-- confirms, после чего проставляет published_at. Это инфраструктурная таблица,
-- доменные сущности вводятся отдельными миграциями (§4 техдока).

CREATE TABLE outbox_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    headers        JSONB,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER     NOT NULL DEFAULT 0
);

-- Частичный индекс под скан неопубликованных событий публикатором.
CREATE INDEX idx_outbox_event_unpublished
    ON outbox_event (id)
    WHERE published_at IS NULL;
