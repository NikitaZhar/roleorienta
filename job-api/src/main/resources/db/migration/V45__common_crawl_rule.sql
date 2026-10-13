-- Правило Common Crawl (стенограмма §86; решение владельца §75): сайт компании из Common Crawl засчитывается, если это
-- её единственный сайт из Common Crawl или в первой части хоста (без www.) есть бренд названия — первое слово
-- company.name_slug от трёх букв. Иначе сайт не засчитан: company_site.status = 'REJECTED' (чужие интернет-магазины с
-- IČO перевозчика в условиях доставки). Дальше правило применяет скан Common Crawl (SiteScanRepository) к компаниям
-- каждого блока; здесь — один раз к уже найденным сайтам. Сайт, подтверждённый потом другим способом
-- (proof не REGISTRATION_NUMBER), правило не трогает.
ALTER TABLE company_site DROP CONSTRAINT company_site_status_check;
ALTER TABLE company_site ADD CONSTRAINT company_site_status_check CHECK (status IN ('FOUND', 'CANDIDATE', 'REJECTED'));

UPDATE company_site s SET status = 'REJECTED'
FROM (SELECT o.id, count(*) OVER (PARTITION BY o.company_id) AS sites,
             length(split_part(c.name_slug, '-', 1)) >= 3
             AND split_part(regexp_replace(o.host, '^www\.', ''), '.', 1)
                 LIKE '%' || split_part(c.name_slug, '-', 1) || '%' AS brand
      FROM company_site o JOIN company c ON c.id = o.company_id
      WHERE o.source = 'COMMON_CRAWL' AND o.proof = 'REGISTRATION_NUMBER' AND o.status = 'FOUND') cc
WHERE s.id = cc.id AND cc.sites > 1 AND NOT cc.brand;

-- Компании, у которых сайт стал не засчитан: найденные сайты перепроверяются (итог компании пересчитает проверка
-- кадровой страницы); найденного сайта не осталось — итог «сайт не найден» (кроме «подключена»).
UPDATE company_site SET checked_at = NULL
WHERE status = 'FOUND' AND company_id IN (SELECT company_id FROM company_site WHERE status = 'REJECTED');

INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
SELECT DISTINCT r.company_id, 'SITE_NOT_FOUND', now(), now() FROM company_site r
WHERE r.status = 'REJECTED'
  AND NOT EXISTS (SELECT 1 FROM company_site f WHERE f.company_id = r.company_id AND f.status = 'FOUND')
ON CONFLICT (company_id) DO UPDATE SET result = 'SITE_NOT_FOUND', checked_at = now()
WHERE company_check.result <> 'CONNECTED';
