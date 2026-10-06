-- Число сотрудников компании из RÚZ — реестра účtovných závierok Словакии (технический документ §4 Company, §5.1;
-- стенограмма §53). Нужно кругу шага «адрес по названию»: компании с 10+ сотрудниками (решение владельца, §48).
--   employees_min        — нижняя граница категории размера RÚZ (velkostOrganizacie: «10-19 zamestnancov» → 10);
--                          NULL — категория не указана («nezistený») или компании нет в RÚZ;
--   employees_checked_at — когда спрашивали RÚZ; повтор — через срок (app.company-size.recheck-after).
ALTER TABLE company
    ADD COLUMN employees_min        INTEGER,
    ADD COLUMN employees_checked_at TIMESTAMPTZ;

-- Основание использования RÚZ Open API: данные — CC0 (https://www.registeruz.sk/cruz-public/home/api); robots.txt
-- сайта API не закрывает.
INSERT INTO source_permission (scope, provider, decision, basis, checked_on) VALUES
('PROVIDER', 'registeruz', 'ALLOW', 'Решение владельца (стенограмма §53): RÚZ Open API — данные CC0; User-Agent с контактом, пауза на хост; берётся только категория числа сотрудников (velkostOrganizacie) по IČO', DATE '2026-10-06');
