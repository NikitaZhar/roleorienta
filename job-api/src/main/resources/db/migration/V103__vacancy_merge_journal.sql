-- Пересмотр «одна вакансия — одна запись» (docs/vacancy-identity-plan.md §5, срез 3; стенограмма §280): журнал
-- склеек, пары «не склеивать» и отношение мест двух публикаций или вакансий — одно правило для пересмотра.

-- 1. Отношение мест (план §4, признак 5): 'same' — у обеих есть города и они пересекаются, или городов нет у обеих, но
--    есть края и они пересекаются; 'different' — так же, но не пересекаются; 'loose' — иначе (общее место у одной или
--    обеих, город против края): решает число вакансий этого названия у работодателя (5б).
CREATE FUNCTION vacancy_places_relation(first TEXT[], second TEXT[]) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN fc <> '{}' AND sc <> '{}' THEN CASE WHEN fc && sc THEN 'same' ELSE 'different' END
                WHEN fc = '{}' AND sc = '{}' AND fr <> '{}' AND sr <> '{}'
                     THEN CASE WHEN fr && sr THEN 'same' ELSE 'different' END
                ELSE 'loose' END
    FROM (SELECT ARRAY(SELECT x FROM unnest(coalesce(first, '{}')) x WHERE x LIKE 'c:%') AS fc,
                 ARRAY(SELECT x FROM unnest(coalesce(first, '{}')) x WHERE x LIKE 'r:%') AS fr,
                 ARRAY(SELECT x FROM unnest(coalesce(second, '{}')) x WHERE x LIKE 'c:%') AS sc,
                 ARRAY(SELECT x FROM unnest(coalesce(second, '{}')) x WHERE x LIKE 'r:%') AS sr) places
$$;

-- 2. Журнал склеек: какая вакансия к какой присоединена, по какому признаку, и всё, что нужно для разделения
--    (ops/vacancy-unmerge.sql): строка присоединённой вакансии, её публикации, отметки и история пользователей,
--    выдача и сводка. Присоединённая вакансия удаляется — ссылок на неё (FOREIGN KEY) нет.
CREATE TABLE vacancy_merge (
    id                BIGSERIAL    PRIMARY KEY,
    kept_vacancy_id   BIGINT       NOT NULL,
    merged_vacancy_id BIGINT       NOT NULL,
    signal            VARCHAR(20)  NOT NULL
                      CHECK (signal IN ('profesia_number', 'address', 'board_id', 'button', 'button_pair',
                                        'title_place')),
    merged_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    merged_vacancy    JSONB        NOT NULL,
    postings          BIGINT[]     NOT NULL,
    status_history    BIGINT[]     NOT NULL,
    user_marks        JSONB,
    delivered         JSONB,
    digest            JSONB,
    undone_at         TIMESTAMPTZ
);
CREATE INDEX vacancy_merge_kept_idx ON vacancy_merge (kept_vacancy_id);
CREATE INDEX vacancy_merge_merged_idx ON vacancy_merge (merged_vacancy_id);

-- 3. Пары «не склеивать» — разделённые вручную: пересмотр их больше не склеивает. Меньший id — первым.
CREATE TABLE vacancy_merge_block (
    vacancy_a  BIGINT      NOT NULL,
    vacancy_b  BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (vacancy_a, vacancy_b),
    CHECK (vacancy_a < vacancy_b)
);
