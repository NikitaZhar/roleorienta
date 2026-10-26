#!/usr/bin/env python3
"""Справочник профессий ISCO-08 / ESCO для миграции job-api (стенограмма §202).

Читает выгрузку ESCO (CSV, «Classification», по языку — target/esco/<язык>/…/ISCOGroups_<язык>.csv и
occupations_<язык>.csv; загружает владелец со страницы https://esco.ec.europa.eu/en/use-esco/download) и русские
названия подразделов и групп ISCO-08 (ops/esco/isco_ru.tsv — собственный перевод, в ESCO русского нет) и пишет
миграцию Flyway с блоками COPY … FROM STDIN (загрузка таблицы из текста одним запросом PostgreSQL,
https://www.postgresql.org/docs/16/sql-copy.html).

Запуск из корня репозитория:  python3 ops/esco/build-migration.py V75__occupation_catalog.sql
Обновление ESCO — новая выгрузка и новая миграция с новым номером (применённые миграции не меняются).
"""
import csv
import glob
import sys

LANGUAGES = ['en', 'sk', 'de']
MIGRATIONS = 'job-api/src/main/resources/db/migration/'
UUID_PREFIX = 'http://data.europa.eu/esco/occupation/'


def esco_file(language, name):
    found = glob.glob(f'target/esco/{language}/*/{name}_{language}.csv')
    if len(found) != 1:
        sys.exit(f'{name}_{language}.csv: found {found}')
    return list(csv.DictReader(open(found[0], encoding='utf-8')))


def copy_value(value):
    if value is None:
        return r'\N'
    return value.replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n').replace('\r', '\\r')


def copy_block(table, columns, rows):
    lines = [f'COPY {table} ({", ".join(columns)}) FROM STDIN;']
    lines += ['\t'.join(copy_value(value) for value in row) for row in rows]
    lines.append('\\.')
    return '\n'.join(lines)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    groups = {row['code']: row for row in esco_file('en', 'ISCOGroups')}
    for code in groups:
        if not code.isdigit() or not 1 <= len(code) <= 4:
            sys.exit(f'unexpected ISCO code {code!r}')
    group_rows = sorted(((code, str(len(code)), code[:-1] or None) for code in groups), key=lambda row: row[0])

    names = []
    for language in LANGUAGES:
        for row in esco_file(language, 'ISCOGroups'):
            names.append((row['code'], language, row['preferredLabel'].strip()))
    russian = dict(line.rstrip('\n').split('\t') for line in open('ops/esco/isco_ru.tsv', encoding='utf-8'))
    shown = {code for code in groups if len(code) in (2, 4)}
    if set(russian) != shown:
        sys.exit(f'isco_ru.tsv: missing {sorted(shown - set(russian))}, extra {sorted(set(russian) - shown)}')
    names += [(code, 'ru', name.strip()) for code, name in sorted(russian.items())]

    occupations = {}
    labels = {}
    for language in LANGUAGES:
        for row in esco_file(language, 'occupations'):
            uri = row['conceptUri']
            if not uri.startswith(UUID_PREFIX) or row['iscoGroup'] not in groups or len(row['iscoGroup']) != 4:
                sys.exit(f'unexpected occupation {uri} {row["iscoGroup"]}')
            occupation = uri[len(UUID_PREFIX):]
            known = occupations.get(occupation)
            if known is not None and known[1] != row['iscoGroup']:
                sys.exit(f'occupation {uri} in two ISCO groups')
            # В выгрузке ESCO 1.2.1 четыре профессии повторены строкой — одна запись, названия без повторов.
            occupations[occupation] = (occupation, row['iscoGroup'], row['code'])
            texts = [(True, row['preferredLabel'])] + [(False, alt) for alt in row['altLabels'].split('\n')]
            for preferred, text in texts:
                label = text.strip()
                key = (occupation, language, label)
                if label and (key not in labels or preferred):
                    labels[key] = 'true' if preferred else 'false'
    labels = [(occupation, language, label, kind) for (occupation, language, label), kind in labels.items()]

    header = f"""-- Справочник профессий ISCO-08 / ESCO (стенограмма §202; проект — технический документ §15, MVP, п. 3б, §201).
-- Источник: ESCO v1.2.1, Еврокомиссия, https://esco.ec.europa.eu (выгрузка «Classification», CSV: {', '.join(LANGUAGES)});
-- русские названия подразделов и групп ISCO-08 — собственный перевод (ops/esco/isco_ru.tsv; в ESCO русского нет).
-- Файл создан скриптом ops/esco/build-migration.py — вручную не править; обновление ESCO — новая миграция.
--   occupation_group      — группы ISCO-08: 1 цифра — раздел, 2 — подраздел (направление на экране), 3 — малая группа,
--                           4 — группа (позиция пользователя); родитель — код без последней цифры.
--   occupation_group_name — названия групп по языкам (en, sk, de — ESCO; ru — перевод, только 2 и 4 цифры).
--   esco_occupation       — профессии ESCO (id — последняя часть адреса ESCO), каждая — в одной группе ISCO-08.
--   esco_label            — названия профессий по языкам: основное и дополнительные (синонимы для поиска позиции и
--                           распознавания вакансий по названию).
CREATE TABLE occupation_group (
    code        VARCHAR(4) PRIMARY KEY CHECK (code ~ '^[0-9]{{1,4}}$'),
    level       SMALLINT   NOT NULL CHECK (level = length(code)),
    parent_code VARCHAR(4) REFERENCES occupation_group (code),
    CHECK ((level = 1) = (parent_code IS NULL))
);

CREATE TABLE occupation_group_name (
    code     VARCHAR(4)   NOT NULL REFERENCES occupation_group (code),
    language VARCHAR(2)   NOT NULL CHECK (language ~ '^[a-z]{{2}}$'),
    name     VARCHAR(300) NOT NULL,
    PRIMARY KEY (code, language)
);

CREATE TABLE esco_occupation (
    id         UUID        PRIMARY KEY,
    group_code VARCHAR(4)  NOT NULL REFERENCES occupation_group (code),
    esco_code  VARCHAR(40) NOT NULL
);

CREATE INDEX esco_occupation_group_idx ON esco_occupation (group_code);

CREATE TABLE esco_label (
    occupation_id UUID         NOT NULL REFERENCES esco_occupation (id),
    language      VARCHAR(2)   NOT NULL CHECK (language ~ '^[a-z]{{2}}$'),
    label         VARCHAR(500) NOT NULL,
    preferred     BOOLEAN      NOT NULL,
    PRIMARY KEY (occupation_id, language, label)
);
"""
    parts = [header,
             copy_block('occupation_group', ['code', 'level', 'parent_code'], group_rows),
             copy_block('occupation_group_name', ['code', 'language', 'name'], names),
             copy_block('esco_occupation', ['id', 'group_code', 'esco_code'], sorted(occupations.values())),
             copy_block('esco_label', ['occupation_id', 'language', 'label', 'preferred'], labels)]
    with open(MIGRATIONS + sys.argv[1], 'w', encoding='utf-8') as out:
        out.write('\n\n'.join(parts) + '\n')
    print(f'groups {len(group_rows)}, names {len(names)}, occupations {len(occupations)}, labels {len(labels)}')


if __name__ == '__main__':
    main()
