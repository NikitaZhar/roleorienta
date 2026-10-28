-- Объявления profesia.sk, которых нет на государственном портале (решение владельца 2026-10-28, стенограмма §219).
--
-- Замер 2026-10-28 (target/profesia-gap.sh, target/profesia-sample.sh): из 14 351 объявления карты сайта profesia.sk у
-- нас нет по номеру 2 710; по выборке из 150 у 77 % той же вакансии нет и в других источниках — около 14 % объявлений
-- profesia.sk (≈ 2 000) не приходят через портал: работа за границей, школы, рабочие, продажи, офис и около 10 % IT.
-- Владелец: добирать всё отсутствующее; приложение показывает только краткие сведения и ссылку на объявление.
--
--   profesia_offer — объявление карты сайта: номер, работодатель в адресе, адрес, когда последний раз в карте сайта;
--                    сведения со страницы объявления (читается один раз, page_read_at): название, работодатель,
--                    место, текст (для сопоставления — не показывается), дата размещения. Страницы нет (404) —
--                    page_read_at без названия: объявление не записывается.
-- Вакансии записываются в источники провайдера profesia (доска — работодатель в адресе), без связи с компанией:
-- связь включила бы правило «один канал на компанию» и отключила бы портал и кадровую страницу компании.
CREATE TABLE profesia_offer (
    num           BIGINT        PRIMARY KEY,
    employer      VARCHAR(200)  NOT NULL,
    url           VARCHAR(1000) NOT NULL,
    listed_at     TIMESTAMPTZ   NOT NULL,
    page_read_at  TIMESTAMPTZ,
    title         VARCHAR(500),
    employer_name VARCHAR(300),
    location      VARCHAR(500),
    content       TEXT,
    posted_at     TIMESTAMPTZ
);

CREATE INDEX profesia_offer_employer_idx ON profesia_offer (employer);
CREATE INDEX profesia_offer_unread_idx ON profesia_offer (num) WHERE page_read_at IS NULL;

UPDATE source_permission SET basis = 'Решение владельца (стенограмма §82, §83, §219): profesia.sk — путь к сайту и '
        || 'кадровой странице компании (объявление → страница компании → ссылка; карта сайта sitemap.php) и вакансии, '
        || 'которых нет на государственном портале: приложение показывает только краткие сведения (название, '
        || 'работодатель, страна, дата) и ссылку на объявление profesia.sk, текст — только для сопоставления; '
        || 'robots.txt соблюдается, User-Agent с контактом, пауза на хост',
    checked_on = DATE '2026-10-28'
WHERE scope = 'PROVIDER' AND provider = 'profesia';
