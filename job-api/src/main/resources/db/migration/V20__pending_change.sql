-- V20 — устойчивый журнал изменений, ожидающих сопоставления с подписками (§8, A16).
-- Пишется в одной транзакции с posting_revision при реальном изменении поля. Не зависит
-- от брокера: уведомление не теряется при сбое между ревизией и рассылкой. Сопоставление
-- (MATCH_SUBSCRIPTIONS, §39) проставит processed_at.

CREATE TABLE pending_change (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_posting_id BIGINT      NOT NULL,
    field_name     TEXT        NOT NULL,
    detected_at    TIMESTAMPTZ NOT NULL,
    processed_at   TIMESTAMPTZ,                  -- NULL = ещё не сопоставлено с подписками
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_pending_change_posting FOREIGN KEY (job_posting_id) REFERENCES job_posting (id)
);

-- Выборка необработанных записей (§39) — частичный индекс по неопубликованным.
CREATE INDEX idx_pending_change_unprocessed ON pending_change (id) WHERE processed_at IS NULL;
