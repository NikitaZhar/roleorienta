-- Адрес стартовой страницы сайта компании (алгоритм поиска сайта версии 3, шаг 2; технический документ §5.1;
-- стенограмма §50). Государственный портал отдаёт «Internetová adresa» полным адресом, иногда с путём
-- (www.firma.sk/sk, firma.com/slovakia): с этой страницы начинается поиск кадровой страницы. NULL — главная
-- хоста (https://<host>/), как у сайтов из Common Crawl и Wikidata.
ALTER TABLE company_site ADD COLUMN start_url VARCHAR(2000);
