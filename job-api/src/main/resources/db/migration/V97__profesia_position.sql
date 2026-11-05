-- Позиции profesia.sk, которые работодатель указал в объявлении (стенограмма §265, §267).
--   Страница объявления profesia.sk содержит строку «Pozícia:» — позиции каталога profesia.sk, выбранные работодателем
--   («Predavač/predavačka, Pokladník/pokladníčka»). Позиция каталога один раз сопоставляется группе ISCO-08, и вакансия
--   подходит условию поиска с этой группой — без угадывания по названию (вакансии H&M «Brigádnik študent…»,
--   Alza «Si zbehlý/á v chatoch…», Accenture «ServiceNow Developer» — все с позициями).
--   profesia_position   — позиция каталога: slug — ключ из адреса /praca/<slug>/; name — название, как на странице;
--                         code — группа ISCO-08 (NULL — не сопоставлена или не распознана); mapped_by — кто сопоставил
--                         (модель распознавания — её имя, например bge-m3; владелец — OWNER; NULL — ещё никто);
--                         ответ модели не перезаписывает решение владельца.
--   profesia_ad_position — позиции объявления по его номеру (внешний id публикации — profesia:<номер>).
--   job_posting.profesia_positions_checked — страница объявления общего списка портала прочитана ради позиций.
--   profesia_offer.positions_read — страница объявления, добранного с profesia.sk, прочитана ради позиций (прочитанные
--                         до §267 перечитываются один раз).
CREATE TABLE profesia_position (
    slug          VARCHAR(200) PRIMARY KEY,
    name          VARCHAR(300) NOT NULL,
    code          VARCHAR(4)   REFERENCES occupation_group (code),
    mapped_by     VARCHAR(80),
    mapped_at     TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE profesia_ad_position (
    num  BIGINT       NOT NULL,
    slug VARCHAR(200) NOT NULL REFERENCES profesia_position (slug),
    PRIMARY KEY (num, slug)
);

CREATE INDEX profesia_ad_position_slug_idx ON profesia_ad_position (slug);

ALTER TABLE job_posting ADD COLUMN profesia_positions_checked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE profesia_offer ADD COLUMN positions_read BOOLEAN NOT NULL DEFAULT FALSE;

-- Публикация объявления profesia.sk по номеру — для представления ниже (внешний id profesia:<номер>).
CREATE INDEX job_posting_profesia_idx ON job_posting (external_id) WHERE external_id LIKE 'profesia:%';

-- Вакансия подходит условию с группой ISCO-08 и через позиции profesia.sk её объявления (§267); прочее — как в V85.
CREATE OR REPLACE VIEW search_condition_match AS
SELECT hit.search_condition_id, hit.code, hit.vacancy_id
FROM (SELECT s.search_condition_id, p.code, m.vacancy_id
      FROM search_condition_position s
      JOIN position p ON p.id = s.position_id
      JOIN vacancy_position_match m ON m.position_id = s.position_id
      UNION ALL
      SELECT o.search_condition_id, o.code, jp.vacancy_id
      FROM search_condition_occupation o
      JOIN job_posting jp ON jp.occupation_code = o.code
      UNION ALL
      SELECT o.search_condition_id, o.code, m.vacancy_id
      FROM search_condition_occupation o
      JOIN position p ON p.occupation_code = o.code
      JOIN vacancy_position_match m ON m.position_id = p.id
      UNION ALL
      SELECT o.search_condition_id, o.code, jp.vacancy_id
      FROM search_condition_occupation o
      JOIN job_posting jp ON jp.title_occupation_code = o.code
      UNION ALL
      SELECT o.search_condition_id, o.code, jp.vacancy_id
      FROM search_condition_occupation o
      JOIN profesia_position pp ON pp.code = o.code
      JOIN profesia_ad_position ap ON ap.slug = pp.slug
      JOIN job_posting jp ON jp.external_id LIKE 'profesia:%' AND jp.external_id = 'profesia:' || ap.num) hit
JOIN vacancy v ON v.id = hit.vacancy_id
WHERE v.posted_at IS NULL OR v.posted_at > now() - interval '4 months';
