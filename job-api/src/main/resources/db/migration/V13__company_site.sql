-- Сайт компании (технический документ §4 CompanySite, §5.1; подэтап 1.7). Связь подтверждена
-- регистрационным номером (IČO) на странице сайта — evidence_url; у компании может быть несколько
-- сайтов, у сайта — несколько компаний (например, группа). source — откуда взята страница
-- (COMMON_CRAWL — архив Common Crawl), crawl — какой обход.
CREATE TABLE company_site (
    id           BIGSERIAL     PRIMARY KEY,
    company_id   BIGINT        NOT NULL REFERENCES company (id),
    host         VARCHAR(255)  NOT NULL,
    evidence_url VARCHAR(2000) NOT NULL,
    source       VARCHAR(20)   NOT NULL,
    crawl        VARCHAR(30),
    found_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (company_id, host)
);

CREATE INDEX company_site_host_idx ON company_site (host);

-- Блоки индекса Common Crawl с адресами зоны .sk, которые предстоит просмотреть в обходе crawl.
-- Блок — сжатый кусок индекса (~3000 адресов) в файле file по смещению block_offset. done — блок
-- просмотрен и его находки записаны в той же транзакции: так скан продолжается с места после
-- остановки.
CREATE TABLE cc_index_block (
    crawl        VARCHAR(30)  NOT NULL,
    seq          INTEGER      NOT NULL,
    file         VARCHAR(200) NOT NULL,
    block_offset BIGINT       NOT NULL,
    block_length INTEGER      NOT NULL,
    done         BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (crawl, seq)
);
