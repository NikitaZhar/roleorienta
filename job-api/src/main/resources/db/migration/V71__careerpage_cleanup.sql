-- Разовая уборка источников «собственная страница вакансий» (careerpage), которые вакансиями не являются (§183).
-- Замер 2026-10-22 (target/careerpage-nonvacancy.sql): из 4 326 открытых «вакансий» careerpage около 70 % — ссылки
-- товаров, меню, новостей сайта. Новые правила разбора (CareerPageList: кадровое слово в пути и подтверждение
-- текстом) мусор больше не подключают, но уже подключённое сами не убирают: нераспознанный список — неполное
-- чтение, прежние публикации не закрываются. Здесь — те же два правила по сохранённым данным: публикация
-- подтверждена, если в пути её адреса есть кадровое слово и в её тексте (job_posting.content) — признак текста
-- вакансии. Источник — мусор, если из открытых публикаций с текстом подтверждена меньше чем половина.
-- Для таких источников: открытые публикации и вакансии закрываются; связь компании с источником снимается; сайты
-- этих компаний проверяются заново (checked_at = NULL) — по новым правилам итог компании пересчитается, и компания
-- без другого канала вернётся к государственному порталу при следующем обходе.

CREATE TEMPORARY TABLE careerpage_junk AS
SELECT jp.source_id
FROM job_posting jp
JOIN source s ON s.id = jp.source_id
WHERE s.provider = 'careerpage' AND jp.closed_at IS NULL AND jp.content IS NOT NULL
GROUP BY jp.source_id
HAVING 2 * count(*) FILTER (
           WHERE regexp_replace(jp.url, '^https?://[^/]+', '')
                     ~* '(kari[eé]r|career|job|pr[aá]c|pozic|ponuk|profesi|vacanc|stellen|nabor)'
             AND jp.content ~* ('(po[žz]iadavk|n[áa]pl[ňn] pr[áa]ce|pon[úu]kame|mzd[ay]|n[áa]stup|pracovn[ýyéeoó] pomer|'
                                || '[úu]v[äa]zok|[žz]ivotopis|requirement|responsibilit|qualification|we offer|salary|'
                                || 'your profile|job description|po[žz]adavk|nab[íi]z[íi]me|anforderung|aufgaben|'
                                || 'wir bieten|ihr profil)'))
       < count(*);

CREATE TEMPORARY TABLE careerpage_junk_company AS
SELECT DISTINCT cs.company_id FROM company_source cs WHERE cs.source_id IN (SELECT source_id FROM careerpage_junk);

UPDATE job_posting SET closed_at = now(), confirmed = FALSE
WHERE closed_at IS NULL AND source_id IN (SELECT source_id FROM careerpage_junk);

UPDATE vacancy v SET state = 'CLOSED', closed_at = now()
WHERE v.state <> 'CLOSED'
  AND EXISTS (SELECT 1 FROM job_posting jp WHERE jp.vacancy_id = v.id
              AND jp.source_id IN (SELECT source_id FROM careerpage_junk))
  AND NOT EXISTS (SELECT 1 FROM job_posting jp WHERE jp.vacancy_id = v.id AND jp.closed_at IS NULL);

DELETE FROM company_source WHERE source_id IN (SELECT source_id FROM careerpage_junk);

UPDATE company_site SET checked_at = NULL
WHERE status = 'FOUND' AND company_id IN (SELECT company_id FROM careerpage_junk_company);

DROP TABLE careerpage_junk_company;
DROP TABLE careerpage_junk;
