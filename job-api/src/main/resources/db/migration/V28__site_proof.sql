-- Чем подтверждён сайт компании и статус находки (технический документ §4 CompanySite, §5.1 «Сайт
-- компании», алгоритм поиска сайта версии 3; стенограмма §48).
--
-- proof — способ подтверждения принадлежности:
--   REGISTRATION_NUMBER — IČO компании рядом с подписью на странице сайта;
--   BRAND               — бренд названия в домене и в видимом тексте страницы;
--   STATE_PORTAL        — «Internetová adresa» из вакансии работодателя на портале (и сайты, взятые с
--                         портала по правилу §47 до этой миграции);
--   PORTAL_MAIL         — домен почты контакта с портала, на сайте IČO или бренд;
--   PORTAL_MAIL_403     — домен почты контакта с портала, сайт отвечает 403;
--   GROUP_SITE          — международный сайт группы (домен почты работодателя или второй источник);
--   WIKIDATA            — официальный сайт из Wikidata по IČO (P8174 → P856);
--   WIKIDATA_403        — сайт из Wikidata отвечает 403;
--   HTTP_403            — угаданный адрес отвечает 403 (только кандидат).
-- status — FOUND: сайт найден и подтверждён; CANDIDATE: вероятно связан с компанией, доказательства нет —
-- не проверяется поиском кадровой страницы и не меняет итог компании.
ALTER TABLE company_site
    ADD COLUMN proof  VARCHAR(30),
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'FOUND' CHECK (status IN ('FOUND', 'CANDIDATE'));

UPDATE company_site SET proof = CASE source WHEN 'COMMON_CRAWL' THEN 'REGISTRATION_NUMBER' ELSE 'STATE_PORTAL' END;

ALTER TABLE company_site
    ALTER COLUMN proof SET NOT NULL,
    ADD CONSTRAINT company_site_proof_check CHECK (proof IN ('REGISTRATION_NUMBER', 'BRAND', 'STATE_PORTAL',
        'PORTAL_MAIL', 'PORTAL_MAIL_403', 'GROUP_SITE', 'WIKIDATA', 'WIKIDATA_403', 'HTTP_403'));
