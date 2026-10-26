-- Предложения позиций от пользователей убраны (§205, решение владельца 2026-10-26): любая профессия есть среди групп
-- ISCO-08 (§204), пользователь выбирает группу сразу, а не ждёт решения администратора. Вместо предложений
-- администратор видит, что искали и не нашли: строка подсказки (без пробелов по краям, в нижнем регистре, не короче
-- 2 знаков), сколько раз и когда последний раз.
DROP TABLE position_suggestion;

CREATE TABLE position_search_miss (
    query   VARCHAR(100) PRIMARY KEY CHECK (length(query) >= 2),
    misses  INTEGER      NOT NULL CHECK (misses > 0),
    last_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX position_search_miss_order_idx ON position_search_miss (misses DESC, last_at DESC);
