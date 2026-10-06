-- Находки по бренду из шага «адрес по названию» до исправления правила бренда (стенограмма §51): у названия из
-- двух слов и больше сайт подтверждался первым словом, а оно бывает нарицательным (Gelateria Paris →
-- gelateria.sk, MÓDA MORA → moda.sk). Такие находки удаляются все, компании проверяются по названию заново
-- (отметка проверки снимается); итог проверки компании, если другого найденного сайта нет, удаляется — его даст
-- новая проверка. Находки по IČO не затрагиваются.
UPDATE company SET site_name_checked_at = NULL
WHERE id IN (SELECT company_id FROM company_site WHERE source = 'NAME' AND proof = 'BRAND');

DELETE FROM company_check k
WHERE k.company_id IN (SELECT company_id FROM company_site WHERE source = 'NAME' AND proof = 'BRAND')
  AND NOT EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = k.company_id AND s.status = 'FOUND'
                  AND NOT (s.source = 'NAME' AND s.proof = 'BRAND'));

DELETE FROM company_site WHERE source = 'NAME' AND proof = 'BRAND';
