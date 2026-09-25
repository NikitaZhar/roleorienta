-- V30 — объединение дублей компаний, заведённых обнаружением до V28 (§85).
-- До ключа идентичности (V28, §80) каждый сайт тенанта Workday получал свою компанию: у тенанта
-- abcsupply — четыре компании. V28 дала ключ «<провайдер>:<тенант>» только старшей (меньший id);
-- остальные — дубли без ключа. Здесь их связи переносятся на компанию с ключом, дубли удаляются.
--
-- Дубль — компания без ключа, у которой ВСЕ связи с источниками ведут к одному ключу и этот ключ
-- уже есть у другой компании. Компании без связей, со связями с разными тенантами или с ключом,
-- которого нет ни у кого, не трогаются. Имя остаётся у компании с ключом (slug-имена исправляет
-- CompanyNameBackfill, §83). Повторный запуск ничего не меняет: после удаления дублей нет.

-- Пары «дубль → компания с ключом». Временная таблица живёт до конца сеанса миграции.
CREATE TEMPORARY TABLE company_merge ON COMMIT DROP AS
SELECT dup.company_id AS duplicate_id, target.id AS target_id
FROM (
    SELECT cs.company_id, min(p.code || ':' || lower(split_part(s.external_ref, '/', 1))) AS identity_key
    FROM company_source cs
    JOIN source s ON s.id = cs.source_id
    JOIN provider p ON p.id = s.provider_id
    JOIN company c ON c.id = cs.company_id
    WHERE c.identity_key IS NULL
    GROUP BY cs.company_id
    HAVING count(DISTINCT p.code || ':' || lower(split_part(s.external_ref, '/', 1))) = 1
) dup
JOIN company target ON target.identity_key = dup.identity_key;

-- Связи компания–источник. После переноса пара (источник, компания) должна остаться одна
-- (UNIQUE (source_id, company_id)): связь дубля удаляется, если у компании с ключом такая связь
-- уже есть или её же получит другой дубль этой компании с меньшим id связи.
DELETE FROM company_source cs
USING company_merge m
WHERE cs.company_id = m.duplicate_id
  AND EXISTS (SELECT 1 FROM company_source other
              LEFT JOIN company_merge om ON om.duplicate_id = other.company_id
              WHERE other.source_id = cs.source_id
                AND other.id <> cs.id
                AND coalesce(om.target_id, other.company_id) = m.target_id
                AND (om.duplicate_id IS NULL OR other.id < cs.id));
UPDATE company_source cs
SET company_id = m.target_id, updated_at = now()
FROM company_merge m
WHERE cs.company_id = m.duplicate_id;

-- Подписки — то же правило (UNIQUE (app_user_id, company_id)): пользователь, подписанный на
-- несколько компаний одного тенанта, сохраняет одну подписку; остальные переносятся.
DELETE FROM company_subscription sub
USING company_merge m
WHERE sub.company_id = m.duplicate_id
  AND EXISTS (SELECT 1 FROM company_subscription other
              LEFT JOIN company_merge om ON om.duplicate_id = other.company_id
              WHERE other.app_user_id = sub.app_user_id
                AND other.id <> sub.id
                AND coalesce(om.target_id, other.company_id) = m.target_id
                AND (om.duplicate_id IS NULL OR other.id < sub.id));
UPDATE company_subscription sub
SET company_id = m.target_id, updated_at = now()
FROM company_merge m
WHERE sub.company_id = m.duplicate_id;

-- Уведомления и кандидаты обнаружения ограничений уникальности по компании не имеют.
UPDATE notification n
SET company_id = m.target_id
FROM company_merge m
WHERE n.company_id = m.duplicate_id;
UPDATE employer_candidate ec
SET company_id = m.target_id, updated_at = now()
FROM company_merge m
WHERE ec.company_id = m.duplicate_id;

-- Ссылок на дубли не осталось — удаляем.
DELETE FROM company c
USING company_merge m
WHERE c.id = m.duplicate_id;
