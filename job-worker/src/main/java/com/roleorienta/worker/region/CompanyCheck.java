package com.roleorienta.worker.region;

import com.roleorienta.worker.region.RegistryCompanyStore.Pending;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Проверка одной компании (план R3–R5, §95): сайт по названию и номеру юрлица, затем карьерная
 * страница и её тип. Итог — с пояснением для отчёта замера (R7).
 */
@Component
public class CompanyCheck {

    private final SiteLocator sites;
    private final CareerPageInspector careers;

    /**
     * @param sites   поиск и подтверждение сайта
     * @param careers карьерная страница и её тип
     */
    public CompanyCheck(SiteLocator sites, CareerPageInspector careers) {
        this.sites = sites;
        this.careers = careers;
    }

    /**
     * Проверяет компанию; ошибки не пробрасываются — итог «ошибка» с причиной.
     *
     * @param company компания из реестра
     * @return итог
     */
    public CheckResult check(Pending company) {
        try {
            Optional<SiteLocator.Site> site = sites.locate(company.name(), company.registryId());
            if (site.isEmpty()) {
                return new CheckResult(null, null, "сайт не найден: ни на одном кандидате домена нет IČO "
                        + company.registryId());
            }
            CareerPage career = careers.inspect(site.get());
            return new CheckResult(site.get().url(), career, describe(career));
        } catch (RuntimeException failure) {
            return new CheckResult(null, null, "ошибка проверки: " + failure.getMessage());
        }
    }

    private static String describe(CareerPage career) {
        if (CareerPage.NONE.equals(career.system())) {
            return "сайт найден, раздела вакансий нет";
        }
        String counted = career.postings() == null ? "вакансии не считались"
                : "вакансий " + career.postings() + ", в нише " + career.nichePostings();
        return "карьерная страница: " + career.system() + ", " + counted;
    }
}
