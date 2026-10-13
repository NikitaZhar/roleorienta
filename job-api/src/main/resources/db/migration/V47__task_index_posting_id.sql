-- 1) Индекс заданий по типу и состоянию (аудит §98, стенограмма §100): запросы цепочек (startChain — hasActive),
--    незавершённых чтений (activeKeys) и очереди группы хоста (claimIfGroupFree) отбирают задания по type и state;
--    таблица task не чистится (~830 тыс. строк), без индекса каждый такой запрос читает её целиком.
--    Создание блокирует запись в task на секунды — при применении миграции job-api.
CREATE INDEX task_type_state_idx ON task (type, state);

-- 2) Идентификатор публикации у источника длиннее 200 символов: у Workday это путь вакансии (externalPath), у
--    schema.org JobPosting — адрес страницы; чтение источника падало целиком («value too long for type character
--    varying(200)», MSD 07–08.10). Длина — как у url (2000). Увеличение длины varchar в PostgreSQL меняет только
--    описание колонки: таблица и уникальный индекс (source_id, external_id) не перестраиваются.
ALTER TABLE job_posting ALTER COLUMN external_id TYPE VARCHAR(2000);
