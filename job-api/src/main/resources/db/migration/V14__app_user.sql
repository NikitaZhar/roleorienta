-- V14 — учётная запись пользователя (§9). Модуль auth: две роли (USER|ADMIN),
-- вход по email, хранение только хэша пароля (§3.9 — открытый пароль не хранится).
-- Соглашения совпадают с остальной схемой: id — IDENTITY, строки — TEXT,
-- время — TIMESTAMPTZ, enum'ы хранятся строками (см. V3). Email уникален без учёта
-- регистра: уникальность строится по lower(email), поэтому одна и та же почта в
-- разном регистре не заводит второй аккаунт.

CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         TEXT        NOT NULL,          -- логин пользователя
    password_hash TEXT        NOT NULL,          -- bcrypt-хэш пароля ({bcrypt}...), не сам пароль
    role          TEXT        NOT NULL,          -- UserRole: USER | ADMIN
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Уникальность email без учёта регистра.
CREATE UNIQUE INDEX uq_app_user_email_lower ON app_user (lower(email));
