-- Ссылка на вакансию у работодателя для объявлений profesia.sk (§209; замечание владельца 2026-10-27, договорённость
-- §178 — вести сразу к вакансии работодателя). Объявление profesia приходит через общий список портала; на его
-- странице кнопка отклика ведёт через переадресацию profesia (/external-reaction-redirect) на страницу работодателя или
-- его системы найма. employer_url — конечный адрес переадресации; NULL — кнопки нет или она ведёт на форму profesia.
-- employer_link_checked — страница объявления уже прочитана ради ссылки (без ссылки повторно не запрашивается).
ALTER TABLE job_posting
    ADD COLUMN employer_url          VARCHAR(2000),
    ADD COLUMN employer_link_checked BOOLEAN NOT NULL DEFAULT FALSE;
