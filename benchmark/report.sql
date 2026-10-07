-- Отчёт по эталону (стенограмма §69): сопоставление с реестром ядра и итоги ядра сейчас.

\echo '1. В реестре ядра: найдено, из них прекращено, не найдено'
SELECT count(b.company_id) AS in_registry,
       count(*) FILTER (WHERE c.terminated_on IS NOT NULL) AS terminated,
       count(*) FILTER (WHERE b.company_id IS NULL) AS not_in_registry
FROM benchmark.company b
LEFT JOIN public.company c ON c.id = b.company_id;

\echo '2. Ядро сейчас: итог проверки компании'
SELECT coalesce(k.result, 'NOT_CHECKED') AS result, count(*) AS companies
FROM benchmark.company b
LEFT JOIN public.company_check k ON k.company_id = b.company_id
WHERE b.company_id IS NOT NULL
GROUP BY 1 ORDER BY 2 DESC;

\echo '3. Ядро сейчас: найден основной сайт'
SELECT count(*) FILTER (WHERE EXISTS (SELECT 1 FROM public.company_site s
                                       WHERE s.company_id = b.company_id AND s.status = 'FOUND')) AS site_found,
       count(*) AS in_registry
FROM benchmark.company b WHERE b.company_id IS NOT NULL;
