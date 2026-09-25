-- V31 — доставка ежедневного дайджеста по email (A7, §88). Одна строка — одно письмо одному
-- пользователю за окно (window_start, window_end]. Письмо собирается заново из данных окна при
-- каждой попытке, поэтому хранится только окно и состояние доставки, не текст.
-- SMTP не exactly-once (technical-design, A16): процесс может упасть после приёма письма
-- сервером — тогда письмо уйдёт повторно (допустимый управляемый повтор). UNIQUE по
-- (пользователь, конец окна) не даёт двум проходам завести одно окно дважды.
-- state: PENDING (ждёт отправки или повтора) | SENT | FAILED (попытки исчерпаны).

CREATE TABLE digest_delivery (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_user_id  BIGINT      NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end   TIMESTAMPTZ NOT NULL,
    state        TEXT        NOT NULL,
    attempts     INTEGER     NOT NULL DEFAULT 0,
    last_error   TEXT,
    sent_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_digest_delivery_user FOREIGN KEY (app_user_id) REFERENCES app_user (id),
    CONSTRAINT uq_digest_delivery_user_window UNIQUE (app_user_id, window_end),
    CONSTRAINT ck_digest_delivery_window CHECK (window_start < window_end),
    CONSTRAINT ck_digest_delivery_state CHECK (state IN ('PENDING', 'SENT', 'FAILED'))
);

-- Очередь отправки: только ждущие письма.
CREATE INDEX idx_digest_delivery_pending ON digest_delivery (id) WHERE state = 'PENDING';
