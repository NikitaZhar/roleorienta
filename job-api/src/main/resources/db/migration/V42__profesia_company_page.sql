-- Шаг «страница компании на profesia.sk» (стенограмма §82): у работодателя портала с объявлением profesia.sk —
-- ссылка объявления на страницу компании на profesia.sk, с неё — ссылка на кадровую страницу (систему найма) или сайт
-- компании.
--   portal_employer.profesia_checked_at — когда страницу компании на profesia.sk смотрели; NULL — не смотрели.
--   company_site.proof = 'PROFESIA'      — сайт или кадровая страница по ссылке со страницы компании на profesia.sk
--                                          (объявление привязано к IČO порталом).
ALTER TABLE portal_employer ADD COLUMN profesia_checked_at TIMESTAMPTZ;

ALTER TABLE company_site DROP CONSTRAINT company_site_proof_check;
ALTER TABLE company_site ADD CONSTRAINT company_site_proof_check CHECK (proof IN ('REGISTRATION_NUMBER', 'BRAND',
    'STATE_PORTAL', 'PORTAL_MAIL', 'PORTAL_MAIL_403', 'GROUP_SITE', 'WIKIDATA', 'WIKIDATA_403', 'HTTP_403', 'PROFESIA'));
