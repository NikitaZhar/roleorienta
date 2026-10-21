-- Сводка новых вакансий на почту (бизнес-описание §6; решения владельца 2026-10-20 и 2026-10-21, стенограмма §164).
--   digest_frequency  — как часто: DAILY (каждый день, по умолчанию), WEEKDAYS (по будням), WEEKLY (по понедельникам),
--                       NEVER (не присылать);
--   time_zone         — часовой пояс браузера пользователя (IANA): письмо — в 8:00 по нему; пока не записан — UTC;
--   digest_sent_until — момент выдачи (delivered_vacancy.delivered_at), до которого вакансии уже попали в письмо;
--                       NULL — писем ещё не было;
--   digest_day        — день по времени пользователя, за который сводка уже отработана (письмо ушло или новых нет).
ALTER TABLE user_account
    ADD COLUMN digest_frequency  VARCHAR(10) NOT NULL DEFAULT 'DAILY'
        CHECK (digest_frequency IN ('DAILY', 'WEEKDAYS', 'WEEKLY', 'NEVER')),
    ADD COLUMN time_zone         VARCHAR(64) NOT NULL DEFAULT 'UTC',
    ADD COLUMN digest_sent_until TIMESTAMPTZ,
    ADD COLUMN digest_day        DATE;
