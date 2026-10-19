-- Личная отметка «скрыть компанию» и отметка администратора «не работодатель» (стенограмма §138).
--   company_hidden      — компания скрыта из поиска и сомнительных только у этого пользователя (как «не подходит» у
--                         вакансий); другие пользователи её видят. Удаление пользователя или компании удаляет отметку.
--   company.not_employer — администратор отметил, что организация не нанимает (профсоюз, ассоциация, объединение
--                         собственников): у всех пользователей группа «не работодатель», нет в сомнительных, адрес
--                         кадровой страницы не принимается; ядро не ищет её сайт по названию и не проверяет её сайты.
CREATE TABLE company_hidden (
    user_id    BIGINT      NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    company_id BIGINT      NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, company_id)
);

ALTER TABLE company ADD COLUMN not_employer BOOLEAN NOT NULL DEFAULT FALSE;
