-- Итог проверки сайта «домен продаётся» (стенограмма §135): сайт переадресует на площадку продажи доменов
-- (hugedomains.com, sedo.com …) — не сайт компании; такой сайт отклоняется (status = 'REJECTED').
ALTER TABLE company_site DROP CONSTRAINT company_site_check_result_check;
ALTER TABLE company_site ADD CONSTRAINT company_site_check_result_check
    CHECK (check_result IN ('SOURCE_FOUND', 'NO_CAREER_PAGE', 'FORMAT_UNSUPPORTED', 'UNREACHABLE', 'USE_FORBIDDEN',
                            'DOMAIN_FOR_SALE'));
