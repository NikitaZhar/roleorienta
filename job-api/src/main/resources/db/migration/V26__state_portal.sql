-- Государственный портал вакансий Služby zamestnanosti (технический документ §5, §5.1; стенограмма §45).
--
-- portal_employer — работодатели из списка портала (/zamestnavatelia): регистрационный номер (IČO) и
-- название; listed_at — когда последний раз был в списке; checked_at — когда проверено, есть ли у
-- работодателя вакансии на портале (вместе с объявлениями profesia.sk, kariera.sk и др., которые портал
-- привязывает к IČO); has_offers — итог последней проверки. Работодатель с вакансиями, который есть в
-- реестре, не кадровое агентство и не имеет своего источника, получает источник портала (провайдер
-- sluzbyzamestnanosti, доска — IČO) — бизнес-описание §4.1, «один канал на компанию».
CREATE TABLE portal_employer (
    registration_number VARCHAR(20)  PRIMARY KEY,
    name                VARCHAR(500) NOT NULL,
    listed_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    checked_at          TIMESTAMPTZ,
    has_offers          BOOLEAN
);

-- Основание использования портала (бизнес-описание §10; технический документ §10).
INSERT INTO source_permission (scope, provider, decision, basis, checked_on) VALUES
('PROVIDER', 'sluzbyzamestnanosti', 'ALLOW', 'Решение владельца (стенограмма §44, §45): государственный портал вакансий; robots.txt запрещает только отклик (/pracovne-ponuky/*/reagovat); отдельных условий использования нет; пауза на хост; контакты не сохраняются; текст служит отбору и не переиздаётся', DATE '2026-10-02');
