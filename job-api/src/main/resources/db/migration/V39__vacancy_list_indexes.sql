-- Индексы под запросы API накопленного списка и сведений о вакансии (аудит §66; контракт §3.5).
-- company_source — по источнику: работодатель и агентство вакансии (подзапросы на каждую строку списка) и проверка
-- «нужно ли название работодателя» в job-worker ищут связи по source_id; первичный ключ начинается с company_id.
CREATE INDEX company_source_source_idx ON company_source (source_id);

-- delivered_vacancy — страница накопленного списка версии условий: порядок (время выдачи, id) по убыванию и курсор
-- «строго после (время, id)». Индекс читается в обратном порядке — строки идут без сортировки всего списка версии.
CREATE INDEX delivered_vacancy_list_idx ON delivered_vacancy (search_condition_id, delivered_at, vacancy_id);

-- unsuitable_mark — страница отмеченных пользователя в порядке отметки, так же.
CREATE INDEX unsuitable_mark_list_idx ON unsuitable_mark (user_id, marked_at, vacancy_id);
