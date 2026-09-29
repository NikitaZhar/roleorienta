-- Обратный путь (технический документ §5.1, вход «индекс систем найма»; §27 стенограммы).
--
-- cc_index_block.purpose — для чего просматривается блок индекса Common Crawl: SITE — страницы зоны
-- .sk (сайты компаний, §25), BOARD — адреса досок систем найма.
ALTER TABLE cc_index_block ADD COLUMN purpose VARCHAR(10) NOT NULL DEFAULT 'SITE';
ALTER TABLE cc_index_block DROP CONSTRAINT cc_index_block_pkey;
ALTER TABLE cc_index_block ADD PRIMARY KEY (crawl, purpose, seq);

-- Доски систем найма, найденные в индексе Common Crawl. slovak — при последней проверке на доске
-- были вакансии в Словакии (тогда доска подключена как источник); checked_at — перепроверка через
-- срок (§17).
CREATE TABLE discovered_board (
    provider   VARCHAR(30)  NOT NULL,
    board      VARCHAR(200) NOT NULL,
    crawl      VARCHAR(30)  NOT NULL,
    checked_at TIMESTAMPTZ,
    slovak     BOOLEAN,
    PRIMARY KEY (provider, board)
);

-- Адрес кадровой страницы сайта компании: для FORMAT_UNSUPPORTED по нему видно, какие системы найма
-- встречаются (выбор следующего адаптера по доле, технический документ §5).
ALTER TABLE company_site ADD COLUMN career_url VARCHAR(2000);
