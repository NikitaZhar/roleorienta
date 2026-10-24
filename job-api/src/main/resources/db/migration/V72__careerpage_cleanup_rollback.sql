-- Откат уборки V71 (§186). Проверка через сутки после V71 (target/careerpage-v71-check.sql, §185): отвязано 230
-- источников careerpage (3 394 публикации), снова открылись только 12; правила уборки теряли настоящие вакансии
-- (кадровое слово только в пути, а не в хосте: careers.priva.com — 30 из 30; признаки текста по всей странице с меню
-- и подвалом). Решение владельца 2026-10-24: лучше лишние страницы, чем потерянные вакансии — уборку откатить, правило
-- подключения в воркере ослабить (CareerPageList). Применённая V71 не меняется.
--
-- Что закрыла V71, узнаётся по её моменту: все её UPDATE ставили closed_at = now() одной транзакции, то есть одно и
-- то же время. Это самое частое closed_at у закрытых публикаций careerpage, чьи источники не связаны ни с одной
-- компанией (связи сняла V71). Публикации и вакансии, закрытые в этот момент и с тех пор не изменённые, открываются;
-- уже открытые снова полным чтением (их 12 источников) не трогаются.
--
-- Связи компаний с источниками V71 удалила без копии; они восстанавливаются по сайту: источник связывается с
-- компанией, у которой найденный сайт (company_site, FOUND) того же регистрируемого домена (две последние части
-- хоста: careers.priva.com — priva.com), или адрес кадровой страницы от пользователя (company_request) — сам адрес
-- источника. Роль — как при проверке сайта (CareerScanRepository.permittedRole): работодатель; кадровое агентство —
-- только с разрешением (source_permission AGENCY ALLOW), без него не связывается. Итог связанной компании —
-- «подключена».

CREATE TEMPORARY TABLE v71_moment AS
SELECT jp.closed_at
FROM job_posting jp
JOIN source s ON s.id = jp.source_id
WHERE s.provider = 'careerpage' AND jp.closed_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM company_source cs WHERE cs.source_id = s.id)
GROUP BY jp.closed_at
ORDER BY count(*) DESC
LIMIT 1;

CREATE TEMPORARY TABLE v71_source AS
SELECT DISTINCT s.id AS source_id, s.board,
       substring(lower(substring(s.board FROM '^https?://([^/:?#]+)')) FROM '([^.]+\.[^.]+)$') AS domain
FROM source s
JOIN job_posting jp ON jp.source_id = s.id
WHERE s.provider = 'careerpage' AND jp.closed_at = (SELECT closed_at FROM v71_moment)
  AND NOT EXISTS (SELECT 1 FROM company_source cs WHERE cs.source_id = s.id);

UPDATE vacancy v SET state = 'ACTIVE', closed_at = NULL
WHERE v.state = 'CLOSED' AND v.closed_at = (SELECT closed_at FROM v71_moment)
  AND EXISTS (SELECT 1 FROM job_posting jp WHERE jp.vacancy_id = v.id
              AND jp.source_id IN (SELECT source_id FROM v71_source)
              AND jp.closed_at = (SELECT closed_at FROM v71_moment));

UPDATE job_posting SET closed_at = NULL, confirmed = TRUE
WHERE closed_at = (SELECT closed_at FROM v71_moment) AND source_id IN (SELECT source_id FROM v71_source);

CREATE TEMPORARY TABLE v71_link AS
SELECT DISTINCT cs.company_id, v.source_id
FROM v71_source v
JOIN company_site cs ON cs.status = 'FOUND'
     AND substring(lower(cs.host) FROM '([^.]+\.[^.]+)$') = v.domain
UNION
SELECT r.company_id, v.source_id
FROM v71_source v
JOIN company_request r ON r.career_url = v.board;

INSERT INTO company_source (company_id, source_id, role)
SELECT l.company_id, l.source_id, CASE WHEN c.agency THEN 'AGENCY' ELSE 'EMPLOYER' END
FROM v71_link l
JOIN company c ON c.id = l.company_id
WHERE NOT c.agency
   OR EXISTS (SELECT 1 FROM source_permission p WHERE p.scope = 'AGENCY' AND p.company_id = c.id
              AND p.decision = 'ALLOW')
ON CONFLICT (company_id, source_id) DO NOTHING;

INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
SELECT DISTINCT cs.company_id, 'CONNECTED', now(), now()
FROM company_source cs
WHERE cs.source_id IN (SELECT source_id FROM v71_source) AND cs.role <> 'GROUP'
ON CONFLICT (company_id) DO UPDATE SET result = 'CONNECTED', checked_at = now();

DROP TABLE v71_link;
DROP TABLE v71_source;
DROP TABLE v71_moment;
