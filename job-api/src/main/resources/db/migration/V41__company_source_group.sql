-- Роль «кадровый сайт группы» (стенограмма §81): доска системы найма на сайте-кандидате компании без найденного сайта
-- (сайт группы, ответ 403). Принадлежность не подтверждена: доска читается с отбором по стране, компания засчитывается,
-- только если у доски есть вакансии в Словакии; итог компании и канал портала она не меняет.
ALTER TABLE company_source DROP CONSTRAINT company_source_role_check;
ALTER TABLE company_source ADD CONSTRAINT company_source_role_check CHECK (role IN ('EMPLOYER', 'AGENCY', 'GROUP'));
