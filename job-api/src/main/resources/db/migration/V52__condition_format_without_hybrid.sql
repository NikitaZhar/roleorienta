-- Формат работы в условиях поиска — любой (NULL), офис или удалённо (решение владельца 2026-10-16, стенограмма
-- §124): гибрида в условиях нет, гибридные вакансии выдаются при «офисе». Сохранённые условия с гибридом
-- становятся «любой формат». Формат вакансии (vacancy.work_format) по-прежнему может быть HYBRID.
UPDATE search_condition SET work_format = NULL WHERE work_format = 'HYBRID';
ALTER TABLE search_condition DROP CONSTRAINT search_condition_work_format_check;
ALTER TABLE search_condition ADD CONSTRAINT search_condition_work_format_check
    CHECK (work_format IN ('OFFICE', 'REMOTE'));
