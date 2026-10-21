-- Статусы вакансии у пользователя и их история (бизнес-описание §6, решения владельца 2026-10-20 и 2026-10-21;
-- стенограмма §156). Заменяют отметку «не подходит» (unsuitable_mark): отметки переносятся как «Не интересно».
--
-- vacancy_user_status — текущий статус пользователя у вакансии; строки нет — вакансия «Новая». Хранится отдельно от
-- истории: после «Вернуть» текущий статус — прежний, до «Не интересно», а не последняя строка истории. Его читают
-- списки (результаты — новые и просмотренные, «Мои отклики», «Не интересно») и выдача job-worker.
CREATE TABLE vacancy_user_status (
    user_id    BIGINT      NOT NULL REFERENCES user_account (id),
    vacancy_id BIGINT      NOT NULL REFERENCES vacancy (id),
    status     VARCHAR(20) NOT NULL
               CHECK (status IN ('VIEWED', 'NOT_INTERESTED', 'APPLIED', 'INTERVIEW', 'REJECTED')),
    changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, vacancy_id)
);

-- Страницы «Мои отклики» и «Не интересно»: статусы пользователя, последние изменённые сверху.
CREATE INDEX vacancy_user_status_list_idx ON vacancy_user_status (user_id, status, changed_at);

-- vacancy_status_history — строки истории, которые ставят пользователь и приложение («Просмотрена»); RETURNED —
-- «Возвращена» из «Не интересно». local_day — день пользователя по часовому поясу браузера в момент записи: статус,
-- поставленный в тот же день, заменяет последнюю строку истории, если это статус пользователя того же дня.
-- «Получена» (первая выдача, delivered_vacancy) и «Закрыта работодателем» (vacancy.closed_at) не хранятся — их
-- собирает API.
CREATE TABLE vacancy_status_history (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES user_account (id),
    vacancy_id BIGINT      NOT NULL REFERENCES vacancy (id),
    status     VARCHAR(20) NOT NULL
               CHECK (status IN ('VIEWED', 'NOT_INTERESTED', 'APPLIED', 'INTERVIEW', 'REJECTED', 'RETURNED')),
    set_at     TIMESTAMPTZ NOT NULL,
    local_day  DATE        NOT NULL
);

CREATE INDEX vacancy_status_history_vacancy_idx ON vacancy_status_history (user_id, vacancy_id, set_at, id);

-- Отметки «не подходит» — «Не интересно» с датой отметки (день — по UTC: пояса пользователя тогда не было).
INSERT INTO vacancy_user_status (user_id, vacancy_id, status, changed_at)
SELECT user_id, vacancy_id, 'NOT_INTERESTED', marked_at FROM unsuitable_mark;

INSERT INTO vacancy_status_history (user_id, vacancy_id, status, set_at, local_day)
SELECT user_id, vacancy_id, 'NOT_INTERESTED', marked_at, (marked_at AT TIME ZONE 'UTC')::date
FROM unsuitable_mark ORDER BY marked_at;

DROP TABLE unsuitable_mark;
