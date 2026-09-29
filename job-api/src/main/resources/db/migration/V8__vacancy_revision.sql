-- История вакансии (технический документ §4 VacancyRevision): изменение значимого поля публикации
-- «было → стало», записанное полным обходом (§6). Время изменения — crawl_run.finished_at.
--   field — TITLE, LOCATION или REOPENED (закрытая публикация появилась снова; значений нет).
-- Текст публикации (HTML) ревизий не создаёт: его изменения технические (§4).
CREATE TABLE vacancy_revision (
    id             BIGSERIAL    PRIMARY KEY,
    vacancy_id     BIGINT       NOT NULL REFERENCES vacancy (id),
    job_posting_id BIGINT       NOT NULL REFERENCES job_posting (id),
    crawl_run_id   BIGINT       NOT NULL REFERENCES crawl_run (id),
    field          VARCHAR(20)  NOT NULL CHECK (field IN ('TITLE', 'LOCATION', 'REOPENED')),
    old_value      VARCHAR(500),
    new_value      VARCHAR(500)
);

CREATE INDEX vacancy_revision_vacancy_idx ON vacancy_revision (vacancy_id);
