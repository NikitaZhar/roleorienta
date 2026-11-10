-- Разовая чистка привязки кадровых страниц к компаниям (срез 1б, стенограмма §290; замер target/s289b-measure.sql,
-- список M7b — решение владельца 2026-11-10). Связь компании с источником ядро не удаляет никогда
-- (CareerScanRepository.link), поэтому правила §290 (gov.sk — у каждого органа свой домен; площадки вакансий и после
-- скриптов; титул и два общих слова — не бренд; имя вразрядку — одно слово) уже записанное не исправят.
--
-- 1. Сайты по бренду, которые новые правила не подтвердят: титул как бренд (ing.sk у «Ing. Milan Singhofer»); два
--    общих слова слитно (spojenaskola.sk; слова — SiteBrand.GENERIC); имя вразрядку — бренд из первых двух букв
--    (P R O T O — pr.sk, K O F T — ko.sk).
--
-- 2. Связи, которые снимаются: кадровая страница на площадке вакансий (profesia.sk без поддомена — мини-сайты
--    компаний *.profesia.sk остаются; job.sk; dentaljobs.sk); кадровая страница другого органа на gov.sk (домен
--    органа не совпадает ни с одним найденным сайтом компании); кадровые страницы компании, чей сайт найден по
--    титулу как бренду (все: ing.sk — careers.ing.com); кадровые страницы на домене удаляемого сайта (п. 1).
CREATE TEMPORARY TABLE v104_generic (word) AS VALUES ('obec'), ('mesto'), ('mestska'), ('skola'), ('zakladna'), ('stredna'), ('spojena'), ('gymnazium'), ('materska'), ('sukromna'), ('senior'), ('seniori'), ('centrum'), ('technicka'), ('technicke'), ('euro'), ('eko'), ('agro'), ('stav'), ('stavby'), ('servis'), ('sluzby'), ('domov'), ('nemocnica'), ('klub'), ('group'), ('trade'), ('invest'), ('energy'), ('auto'), ('transport'), ('logistics'), ('logistika'), ('consulting'), ('system'), ('systems'), ('tech'), ('media'), ('plus'), ('slovak'), ('slovensko'), ('slovakia'), ('sk'), ('sr'), ('union'), ('global'), ('service'), ('services'), ('company'), ('spolocnost'), ('druzstvo'), ('podnik'), ('farma'), ('market'), ('obchod'), ('real'), ('reality'), ('smart'), ('green'), ('best'), ('top'), ('super'), ('slovenske'), ('holding'), ('stores');

CREATE TEMPORARY TABLE v104_site AS
SELECT st.id, st.company_id, regexp_replace(lower(st.host), '^www\.', '') AS host,
       (c.name ~* '^(ing|mgr|mudr|mvdr|mddr|judr|phdr|rndr|paeddr|bc|dr|doc|prof|phd)\.\s') AS by_title
FROM company_site st JOIN company c ON c.id = st.company_id
WHERE st.source = 'NAME' AND st.proof = 'BRAND'
  AND ((c.name ~* '^(ing|mgr|mudr|mvdr|mddr|judr|phdr|rndr|paeddr|bc|dr|doc|prof|phd)\.\s'
        AND split_part(regexp_replace(lower(st.host), '^www\.', ''), '.', 1) = lower(split_part(c.name, '.', 1)))
    OR (split_part(regexp_replace(lower(st.host), '^www\.', ''), '.', 1)
            = split_part(c.name_slug, '-', 1) || split_part(c.name_slug, '-', 2)
        AND split_part(c.name_slug, '-', 1) IN (SELECT word FROM v104_generic)
        AND split_part(c.name_slug, '-', 2) IN (SELECT word FROM v104_generic))
    OR (c.name ~ '^\s*\S\s+\S\s+'
        AND split_part(regexp_replace(lower(st.host), '^www\.', ''), '.', 1)
            = lower(substring(c.name FROM '^\s*(\S)') || substring(c.name FROM '^\s*\S\s+(\S)'))));

CREATE TEMPORARY TABLE v104_source AS
SELECT s.id, regexp_replace(lower(split_part(split_part(split_part(
           CASE WHEN s.board LIKE '%://%' THEN split_part(s.board, '://', 2) ELSE s.board END, '/', 1), '?', 1), ':', 1)),
         '^www\.', '') AS host
FROM source s WHERE s.provider = 'careerpage' AND s.closed_at IS NULL;

CREATE TEMPORARY TABLE v104_link AS
SELECT cs.company_id, cs.source_id
FROM company_source cs JOIN v104_source s ON s.id = cs.source_id
WHERE s.host IN ('profesia.sk', 'profesia.cz', 'job.sk', 'dentaljobs.sk')
   OR (s.host LIKE '%.gov.sk'
       AND NOT EXISTS (SELECT 1 FROM company_site st, LATERAL (SELECT regexp_replace(lower(st.host), '^www\.', '') AS h,
                                                                        substring(s.host FROM '([^.]+\.gov\.sk)$') AS d) x
                       WHERE st.company_id = cs.company_id AND st.status = 'FOUND'
                         AND (x.h = x.d OR right(x.h, length(x.d) + 1) = '.' || x.d)))
   OR cs.company_id IN (SELECT company_id FROM v104_site WHERE by_title)
   OR EXISTS (SELECT 1 FROM v104_site v WHERE v.company_id = cs.company_id
              AND (s.host = v.host OR right(s.host, length(v.host) + 1) = '.' || v.host));

DELETE FROM company_source cs USING v104_link l WHERE cs.company_id = l.company_id AND cs.source_id = l.source_id;

-- 3. Источник, у которого не осталось ни одной компании, закрывается, как закрывает администратор (§206,
--    AdminSourceRepository): вакансии без другой открытой публикации, открытые публикации, сам источник.
CREATE TEMPORARY TABLE v104_orphan AS
SELECT DISTINCT l.source_id AS id FROM v104_link l
WHERE NOT EXISTS (SELECT 1 FROM company_source cs WHERE cs.source_id = l.source_id);

UPDATE vacancy v SET state = 'CLOSED', closed_at = now()
WHERE v.state <> 'CLOSED'
  AND EXISTS (SELECT 1 FROM job_posting p WHERE p.vacancy_id = v.id AND p.closed_at IS NULL
                AND p.source_id IN (SELECT id FROM v104_orphan))
  AND NOT EXISTS (SELECT 1 FROM job_posting p WHERE p.vacancy_id = v.id AND p.closed_at IS NULL
                    AND p.source_id NOT IN (SELECT id FROM v104_orphan));

UPDATE job_posting SET closed_at = now(), confirmed = FALSE
WHERE closed_at IS NULL AND source_id IN (SELECT id FROM v104_orphan);

UPDATE source SET closed_at = now() WHERE id IN (SELECT id FROM v104_orphan) AND closed_at IS NULL;

-- 4. Удалённые сайты: компании проверяются по названию заново (как V32), итог проверки компании без другого
--    найденного сайта удаляется — его даст новая проверка.
UPDATE company SET site_name_checked_at = NULL WHERE id IN (SELECT company_id FROM v104_site);

DELETE FROM company_check k
WHERE k.company_id IN (SELECT company_id FROM v104_site)
  AND NOT EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = k.company_id AND s.status = 'FOUND'
                  AND s.id NOT IN (SELECT id FROM v104_site));

DELETE FROM company_site WHERE id IN (SELECT id FROM v104_site);

DROP TABLE v104_generic, v104_site, v104_source, v104_link, v104_orphan;
