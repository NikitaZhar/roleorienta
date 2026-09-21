-- V21 — внутренние уведомления пользователя об изменениях в вакансиях подписанных
-- компаний (§7, §8, §39). Создаёт MATCH_SUBSCRIPTIONS (job-worker), читает job-api по
-- владельцу (A23). Владелец — app_user_id; связи на app_user/job_posting/company — FK.

CREATE TABLE notification (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_user_id    BIGINT      NOT NULL,
    job_posting_id BIGINT      NOT NULL,
    company_id     BIGINT      NOT NULL,
    field_name     TEXT        NOT NULL,
    read_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_user    FOREIGN KEY (app_user_id)    REFERENCES app_user (id),
    CONSTRAINT fk_notification_posting FOREIGN KEY (job_posting_id) REFERENCES job_posting (id),
    CONSTRAINT fk_notification_company FOREIGN KEY (company_id)     REFERENCES company (id)
);

-- Список уведомлений пользователя, новые сверху.
CREATE INDEX idx_notification_user ON notification (app_user_id, id DESC);
