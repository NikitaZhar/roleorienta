-- Выдача порциями (бизнес-описание §3, §4.5, §6; технический документ §4, §7, §16.15; подэтап 1.5).
--
-- user_account — учётная запись: email и хеш пароля (вход — следующий срез).
CREATE TABLE user_account (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- search_condition — версия условий поиска: страны (ISO 3166-1 alpha-2), позиция словаря, формат
-- (NULL — не задан). Смена стран, позиции или формата — новая версия с пустым накопленным списком;
-- лимит порции меняется в той же версии и список не сбрасывает. Активна одна версия на пользователя.
CREATE TABLE search_condition (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES user_account (id),
    countries     TEXT[]      NOT NULL CHECK (cardinality(countries) > 0),
    position_id   BIGINT      NOT NULL REFERENCES position (id),
    work_format   VARCHAR(10) CHECK (work_format IN ('OFFICE', 'HYBRID', 'REMOTE')),
    portion_limit INTEGER     NOT NULL CHECK (portion_limit > 0),
    active        BOOLEAN     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX search_condition_active_idx ON search_condition (user_id) WHERE active;

-- pass_run — проход по расписанию; окно (начало часа) уникально: повторный тик того же окна
-- прохода не добавляет.
CREATE TABLE pass_run (
    id           BIGSERIAL   PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- delivered_vacancy — накопленный список версии условий и одновременно место остановки: вакансия
-- выдаётся по версии не более одного раза (первичный ключ); проход — какой порцией выдана.
CREATE TABLE delivered_vacancy (
    search_condition_id BIGINT      NOT NULL REFERENCES search_condition (id),
    vacancy_id          BIGINT      NOT NULL REFERENCES vacancy (id),
    pass_run_id         BIGINT      NOT NULL REFERENCES pass_run (id),
    delivered_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (search_condition_id, vacancy_id)
);

CREATE INDEX delivered_vacancy_pass_idx ON delivered_vacancy (search_condition_id, pass_run_id);

-- unsuitable_mark — отметка «не подходит»: пользователь + вакансия, не зависит от версии условий.
CREATE TABLE unsuitable_mark (
    user_id    BIGINT      NOT NULL REFERENCES user_account (id),
    vacancy_id BIGINT      NOT NULL REFERENCES vacancy (id),
    marked_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, vacancy_id)
);
