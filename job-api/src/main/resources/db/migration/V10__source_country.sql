-- Область чтения по стране (технический документ §5 «Фильтр по стране», §4 CrawlRun):
--   source.country    — страна источника (ISO 3166-1 alpha-2, например SK); NULL — без фильтра.
--                       Провайдер с фильтром по стране (Workday) читает только её публикации;
--   crawl_run.country — область обхода, неизменная после начала: отсутствие засчитывается
--                       только полным обходом той же области.
ALTER TABLE source    ADD COLUMN country CHAR(2);
ALTER TABLE crawl_run ADD COLUMN country CHAR(2);
