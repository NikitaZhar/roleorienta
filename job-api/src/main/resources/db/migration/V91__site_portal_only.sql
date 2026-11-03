-- Итог проверки сайта PORTAL_ONLY (стенограмма §251): своего списка вакансий нет — кадровая страница ведёт на страницу
-- компании на площадке вакансий (profesia.sk, jobs.cz) или переадресует на неё; вакансии компании берутся с портала и
-- profesia.sk. В мере полноты (company_coverage) такая компания — NO_OWN_PAGE, как при отметке администратора.
ALTER TABLE company_site DROP CONSTRAINT company_site_check_result_check;
ALTER TABLE company_site ADD CONSTRAINT company_site_check_result_check
    CHECK (check_result IN ('SOURCE_FOUND', 'NO_CAREER_PAGE', 'FORMAT_UNSUPPORTED', 'UNREACHABLE', 'USE_FORBIDDEN',
                            'DOMAIN_FOR_SALE', 'PORTAL_ONLY'));
