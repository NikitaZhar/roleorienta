-- Шаг «список работодателей profesia.sk» (стенограмма §83; алгоритм владельца, п. 3): работодатели страны — из карты
-- сайта profesia.sk (sitemap.php: объявления /praca/<работодатель>/O<номер>), по одному объявлению на работодателя →
-- страница компании на profesia.sk → кадровая страница или сайт. Работодатель profesia.sk сопоставляется компании
-- реестра по названию: <работодатель> в адресе — название без диакритики, через дефис.
--   company.name_slug           — название компании в том же виде; правовая форма в конце (s.r.o., a.s., k.s., …)
--                                 отброшена. Сопоставление — только однозначное (одна действующая компания).
--   profesia_employer           — работодатель из карты сайта: объявление, компания реестра (NULL — не сопоставлен),
--                                 когда был в карте сайта, когда смотрели страницу компании.
--   company_site.source = 'PROFESIA' — сайт со страницы компании, найденной через список работодателей.
ALTER TABLE company ADD COLUMN name_slug TEXT GENERATED ALWAYS AS (
    regexp_replace(
        btrim(regexp_replace(lower(translate(name,
            'áäàâãåčćçďéěëèêíìîïĺľłňńñóôöòõőŕřšśťúůüùûűýÿžźżÁÄÀÂÃÅČĆÇĎÉĚËÈÊÍÌÎÏĹĽŁŇŃÑÓÔÖÒÕŐŔŘŠŚŤÚŮÜÙÛŰÝŸŽŹŻ',
            'aaaaaacccdeeeeeiiiilllnnnoooooorrsstuuuuuuyyzzzaaaaaacccdeeeeeiiiilllnnnoooooorrsstuuuuuuyyzzz')),
            '[^a-z0-9]+', '-', 'g'), '-'),
        '(-(spol-s-r-o|s-r-o|sro|a-s|as|k-s|v-o-s|s-e|se|j-s-a|v-d|druzstvo|o-z|organizacna-zlozka'
            || '|odstepny-zavod|gmbh|ag|ltd|inc|kft|sp-z-o-o))+$', '')) STORED;

CREATE INDEX company_name_slug ON company (name_slug);

CREATE TABLE profesia_employer (
    slug       VARCHAR(200)  PRIMARY KEY,
    ad_url     VARCHAR(1000) NOT NULL,
    company_id BIGINT        REFERENCES company (id),
    listed_at  TIMESTAMPTZ   NOT NULL,
    checked_at TIMESTAMPTZ
);
