-- Сайт работодателя с государственного портала (§47): из детали его вакансии на портале —
-- «Internetová adresa», иначе домен контактной почты. Записывается в company_site с source
-- STATE_PORTAL; связь с IČO даёт сам портал. site_checked_at — когда сайт искали; повтор — через срок
-- перепроверки портала.
ALTER TABLE portal_employer ADD COLUMN site_checked_at TIMESTAMPTZ;
