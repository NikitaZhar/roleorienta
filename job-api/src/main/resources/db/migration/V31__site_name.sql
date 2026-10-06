-- Шаги 3–4 алгоритма поиска сайта версии 3 — адрес по названию (технический документ §5.1; стенограмма §51).
-- site_name_checked_at — когда для компании проверяли адреса из её названия; повтор — через срок перепроверки
-- (app.site-name.recheck-after). Находки и кандидаты пишутся в company_site с source = 'NAME'.
ALTER TABLE company ADD COLUMN site_name_checked_at TIMESTAMPTZ;
