-- Итог проверки компании с причиной (бизнес-описание §4.2: «результат сохраняется в любом случае»;
-- технический документ §4 DiscoveryAttempt, §5.1; стенограмма §39). Одна строка на компанию —
-- последний итог; история попыток не хранится.
--   CONNECTED          — источник вакансий поддерживаемого формата подключён;
--   SITE_NOT_FOUND     — скан обхода Common Crawl завершён, сайт с IČO компании не найден;
--   PAGE_NOT_FOUND     — сайт есть, кадровой страницы нет;
--   FORMAT_UNSUPPORTED — кадровая страница есть, формат не поддерживается;
--   SOURCE_UNAVAILABLE — сайт не ответил.
-- Нет строки — компания ещё не получила итога (первичный обход не дошёл до неё).
-- У компании с несколькими сайтами итог — лучший из итогов её сайтов.
CREATE TABLE company_check (
    company_id       BIGINT      PRIMARY KEY REFERENCES company (id),
    result           VARCHAR(30) NOT NULL CHECK (result IN ('CONNECTED', 'SITE_NOT_FOUND', 'PAGE_NOT_FOUND',
                                                            'FORMAT_UNSUPPORTED', 'SOURCE_UNAVAILABLE')),
    first_checked_at TIMESTAMPTZ NOT NULL,
    checked_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX company_check_result_idx ON company_check (result);

-- Итоги уже проверенных сайтов.
INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
SELECT company_id,
       CASE max(CASE check_result WHEN 'SOURCE_FOUND' THEN 4 WHEN 'FORMAT_UNSUPPORTED' THEN 3
                                  WHEN 'NO_CAREER_PAGE' THEN 2 ELSE 1 END)
           WHEN 4 THEN 'CONNECTED' WHEN 3 THEN 'FORMAT_UNSUPPORTED' WHEN 2 THEN 'PAGE_NOT_FOUND'
           ELSE 'SOURCE_UNAVAILABLE' END,
       min(checked_at), max(checked_at)
FROM company_site
WHERE check_result IS NOT NULL
GROUP BY company_id;

-- Сайт не найден: скан хотя бы одного обхода завершён, у действующей компании сайта нет.
INSERT INTO company_check (company_id, result, first_checked_at, checked_at)
SELECT c.id, 'SITE_NOT_FOUND', now(), now()
FROM company c
WHERE c.terminated_on IS NULL
  AND NOT EXISTS (SELECT 1 FROM company_site s WHERE s.company_id = c.id)
  AND EXISTS (SELECT 1 FROM cc_index_block b WHERE b.purpose = 'SITE'
              GROUP BY b.crawl HAVING bool_and(b.done))
ON CONFLICT (company_id) DO NOTHING;
