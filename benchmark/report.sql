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


\echo '4. Ядро сейчас: кадровая страница — лучший итог среди найденных сайтов компании (сравнимо с группами замера топ-500, §72)'
SELECT CASE rank WHEN 1 THEN '1 page found, read'
                 WHEN 2 THEN '2 page found, format unsupported'
                 WHEN 3 THEN '3 no page'
                 WHEN 4 THEN '4 site unreachable'
                 WHEN 5 THEN '5 use forbidden'
                 WHEN 6 THEN '6 site not checked'
                 ELSE '7 no site' END AS career_page,
       count(*) AS companies
FROM (SELECT b.company_id,
             min(CASE WHEN s.id IS NULL THEN 7
                      WHEN s.check_result = 'SOURCE_FOUND' THEN 1
                      WHEN s.check_result = 'FORMAT_UNSUPPORTED' THEN 2
                      WHEN s.check_result = 'NO_CAREER_PAGE' THEN 3
                      WHEN s.check_result = 'UNREACHABLE' THEN 4
                      WHEN s.check_result = 'USE_FORBIDDEN' THEN 5
                      ELSE 6 END) AS rank
      FROM benchmark.company b
      LEFT JOIN public.company_site s ON s.company_id = b.company_id AND s.status = 'FOUND'
      WHERE b.company_id IS NOT NULL
      GROUP BY b.company_id) best
GROUP BY 1 ORDER BY 1;

\echo '5. Ядро сейчас: CONNECTED по каналу — только государственный портал или свой источник'
SELECT channel, count(*) AS companies
FROM (SELECT b.company_id,
             CASE WHEN bool_and(src.provider = 'sluzbyzamestnanosti') THEN 'state portal only'
                  ELSE 'own source' END AS channel
      FROM benchmark.company b
      JOIN public.company_check k ON k.company_id = b.company_id AND k.result = 'CONNECTED'
      JOIN public.company_source cs ON cs.company_id = b.company_id
      JOIN public.source src ON src.id = cs.source_id
      GROUP BY b.company_id) per_company
GROUP BY 1 ORDER BY 1;
