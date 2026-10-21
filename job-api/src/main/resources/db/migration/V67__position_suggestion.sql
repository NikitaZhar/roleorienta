-- Предложения позиций от пользователей (бизнес-описание §3; стенограмма §161): нужной позиции в словаре нет —
-- пользователь её предлагает; администратор добавляет позицию в словарь (ADDED, position_id — какая) или отклоняет
-- (REJECTED, причина необязательна); решение можно изменить. Новые (PENDING) по created_at найдёт оповещение
-- администратора (шаг 4 «Почта и оповещения»).
CREATE TABLE position_suggestion (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES user_account (id),
    text        VARCHAR(500) NOT NULL CHECK (btrim(text) <> ''),
    state       VARCHAR(10)  NOT NULL CHECK (state IN ('PENDING', 'ADDED', 'REJECTED')),
    position_id BIGINT       REFERENCES position (id),
    reason      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at  TIMESTAMPTZ,
    CHECK ((state = 'ADDED') = (position_id IS NOT NULL)),
    CHECK (state = 'REJECTED' OR reason IS NULL),
    CHECK ((state = 'PENDING') = (decided_at IS NULL))
);

-- Свои предложения пользователя и лимит в сутки — по пользователю и времени.
CREATE INDEX position_suggestion_user_idx ON position_suggestion (user_id, created_at);
