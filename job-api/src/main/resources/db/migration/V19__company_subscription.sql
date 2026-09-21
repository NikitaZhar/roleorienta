-- V19 — подписка пользователя на компанию (§4, §7, A23).
-- Персональная запись: проверяется по владельцу, данные одного пользователя недоступны
-- другому. Пара (app_user_id, company_id) уникальна — идемпотентность подписки.
-- Стиль как в V16/V18: TIMESTAMPTZ для времени, явные FK.

CREATE TABLE company_subscription (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_user_id  BIGINT      NOT NULL,
    company_id   BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_company_subscription_user    FOREIGN KEY (app_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_company_subscription_company FOREIGN KEY (company_id)  REFERENCES company (id),
    CONSTRAINT uq_company_subscription_user_company UNIQUE (app_user_id, company_id)
);

CREATE INDEX idx_company_subscription_user ON company_subscription (app_user_id);
