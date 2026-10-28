-- Аудит §222: страница объявления profesia.sk без названия (баннер, капча, смена разметки) — не «объявления нет».
--   profesia_offer.page_gone — страницы нет (404 / 410, запрет robots.txt): больше не запрашивается. Страница без
--                              названия — page_gone FALSE и title NULL: запрашивается снова через 7 дней.
ALTER TABLE profesia_offer ADD COLUMN page_gone BOOLEAN NOT NULL DEFAULT FALSE;
