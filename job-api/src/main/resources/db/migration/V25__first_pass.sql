-- Первичный обход страны и проверка досок по странам сбора (бизнес-описание §4.1; технический документ
-- §4 CollectionCountry, §5.1; стенограмма §42).
--   registry_read_at   — реестр страны впервые прочитан до конца (читать больше нечего);
--   first_pass_done_at — первичный обход завершён: реестр прочитан и у каждой действующей компании
--                        страны есть итог проверки (company_check).
ALTER TABLE collection_country
    ADD COLUMN registry_read_at   TIMESTAMPTZ,
    ADD COLUMN first_pass_done_at TIMESTAMPTZ;

-- Найденная доска: страна сбора, где у неё есть вакансии (было: признак «Словакия»).
ALTER TABLE discovered_board ADD COLUMN country CHAR(2);
UPDATE discovered_board SET country = 'SK' WHERE slovak;
ALTER TABLE discovered_board DROP COLUMN slovak;
