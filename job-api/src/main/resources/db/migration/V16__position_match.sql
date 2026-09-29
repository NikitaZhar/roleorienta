-- Отбор по позиции (технический документ §4 Position, VacancyPositionMatch; §6, §16.13;
-- подэтап 1.4). Словарь позиций — данные с версией (файл positions.json приложения job-worker);
-- position — его позиции по коду, для ссылок из соответствий и условий поиска.
CREATE TABLE position (
    id   BIGSERIAL    PRIMARY KEY,
    code VARCHAR(60)  NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL
);

-- Предрасчитанное соответствие вакансии позиции: версия словаря и объяснение (какое название или
-- признак сработали). Пересчитывается при изменении вакансии или версии словаря.
CREATE TABLE vacancy_position_match (
    vacancy_id         BIGINT      NOT NULL REFERENCES vacancy (id),
    position_id        BIGINT      NOT NULL REFERENCES position (id),
    dictionary_version VARCHAR(40) NOT NULL,
    explanation        VARCHAR(300) NOT NULL,
    PRIMARY KEY (vacancy_id, position_id)
);

CREATE INDEX vacancy_position_match_position_idx ON vacancy_position_match (position_id);

-- Версия словаря, по которой вакансия сопоставлена; NULL — сопоставить (новая или изменилась).
ALTER TABLE vacancy ADD COLUMN match_version VARCHAR(40);
CREATE INDEX vacancy_match_version_idx ON vacancy (match_version);
