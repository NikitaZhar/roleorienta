-- Transactional outbox (технический документ §6, §9): событие записывается в одной транзакции
-- с изменением данных; публикатор job-worker отправляет его в RabbitMQ и отмечает published_at.
CREATE TABLE outbox_event (
    id           BIGSERIAL    PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0
);

-- Частичный индекс: публикатор выбирает только неопубликованные события, по порядку id.
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (id) WHERE published_at IS NULL;
