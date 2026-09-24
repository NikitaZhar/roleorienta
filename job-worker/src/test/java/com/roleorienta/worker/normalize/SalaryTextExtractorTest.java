package com.roleorienta.worker.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.core.domain.SalaryBasis;
import com.roleorienta.core.domain.SalaryPeriod;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Модульные тесты извлечения зарплаты из текста (§6, A09; §66). Фрагменты — дословные
 * формулировки из живых вакансий Workday SK/AT (Hitachi, DXC, IQVIA; 2026-09-24).
 * Проверяют: разбор европейских и английских записей сумм, форму (диапазон / только min /
 * только max / точная), явные период и базу и UNKNOWN без них, отсев льгот
 * (Essenszuschuss, cafeteria, meal vouchers), процентов, оборотов и нулевых шаблонов.
 */
class SalaryTextExtractorTest {

    private final SalaryTextExtractor extractor = new SalaryTextExtractor();

    private NormalizedSalary salary(String text) {
        return extractor.extract(text).salary();
    }

    // --- Австрия: минимальный оклад по коллективному договору ---

    @Test
    void austrianCollectiveAgreementMonthlyGrossSkipsMealAllowance() {
        NormalizedSalary found = salary("Frisches Obst und Mittagessen in der Betriebskantine "
                + "(Tgl. Essenszuschuss i. H. v. 8€) Zuschuss zur Jahreskarte für öffentliche Verkehrsmittel "
                + "Das Mindestgrundgehalt gemäß des Kollektivvertrages (Mitarbeitende in der Elektroindustrie) "
                + "startet bei EUR 3.348,62 brutto pro Monat. Wobei für uns eine marktkonforme Überzahlung "
                + "je nach Qualifikation und Erfahrung selbstverständlich ist.");
        assertThat(found.min()).isEqualByComparingTo("3348.62");
        assertThat(found.max()).isNull();
        assertThat(found.currency()).isEqualTo("EUR");
        assertThat(found.period()).isEqualTo(SalaryPeriod.MONTH);
        assertThat(found.basis()).isEqualTo(SalaryBasis.GROSS);
    }

    @Test
    void austrianMinimumWithoutPeriodWordKeepsPeriodUnknown() {
        NormalizedSalary found = salary("Wir bieten eine attraktive Vergütung, die das Mindestgehalt aus "
                + "Rahmenkollektivvertrag der Elektro- und Elektronikindustrie für diese Position "
                + "(3.775,25 € in Vollzeit) übertrifft und mit Ihnen gemeinsam vereinbart wird.");
        assertThat(found.min()).isEqualByComparingTo("3775.25");
        assertThat(found.max()).isNull();
        assertThat(found.period()).isEqualTo(SalaryPeriod.UNKNOWN);
        assertThat(found.basis()).isEqualTo(SalaryBasis.UNKNOWN);
    }

    @Test
    void spaceAfterDecimalCommaIsTolerated() {
        assertThat(salary("das Mindestgehalt aus dem Rahmenkollektivvertrag für diese Position "
                + "(3.775, 25 € in Vollzeit) übersteigen kann.").min()).isEqualByComparingTo("3775.25");
    }

    @Test
    void austrianYearlyWithSpaceThousands() {
        NormalizedSalary found = salary("Für diese Position gilt das kollektivvertragliche Mindestgehalt "
                + "von 76 216 EUR p.a. brutto. At DXC Technology");
        assertThat(found.min()).isEqualByComparingTo("76216");
        assertThat(found.max()).isNull();
        assertThat(found.period()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(found.basis()).isEqualTo(SalaryBasis.GROSS);
    }

    @Test
    void englishStartsAtYearlyGross() {
        NormalizedSalary found = salary("WE OFFER The salary for this position starts at € 60.000 gross p.a. "
                + "Actual compensation is based on experience and qualification.");
        assertThat(found.min()).isEqualByComparingTo("60000");
        assertThat(found.max()).isNull();
        assertThat(found.period()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(found.basis()).isEqualTo(SalaryBasis.GROSS);
    }

    @Test
    void minimumMentionedFarBeforeAmountStillMeansMinOnly() {
        NormalizedSalary found = salary("The final salary may exceed the minimum salary defined by the Austrian "
                + "collective agreement for the Electrical and Electronics Industry for this position "
                + "(4.645,81€ gross per month on a full-time basis), depending on qualifications");
        assertThat(found.min()).isEqualByComparingTo("4645.81");
        assertThat(found.max()).isNull();
        assertThat(found.period()).isEqualTo(SalaryPeriod.MONTH);
    }

    @Test
    void periodWordBeforeAmount() {
        NormalizedSalary found = salary("Monthly gross salary: starting at 3.000 EUR basic - an overpay "
                + "according to skills and working experience is possible.");
        assertThat(found.min()).isEqualByComparingTo("3000");
        assertThat(found.period()).isEqualTo(SalaryPeriod.MONTH);
        assertThat(found.basis()).isEqualTo(SalaryBasis.GROSS);
    }

    // --- Словакия: диапазоны и минимум ---

    @Test
    void rangeWithCurrencyOnBothSides() {
        NormalizedSalary found = salary("We offer salary from EUR 40.000 – EUR 60.000 gross annual. "
                + "The indicated salary range is provided for transparency purposes");
        assertThat(found.min()).isEqualByComparingTo("40000");
        assertThat(found.max()).isEqualByComparingTo("60000");
        assertThat(found.period()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(found.basis()).isEqualTo(SalaryBasis.GROSS);
    }

    @Test
    void monthlyRangeVariants() {
        NormalizedSalary withCurrencyFirst = salary("We offer salary from EUR 3000 – 4500 gross per month.");
        assertThat(withCurrencyFirst.min()).isEqualByComparingTo("3000");
        assertThat(withCurrencyFirst.max()).isEqualByComparingTo("4500");
        assertThat(withCurrencyFirst.period()).isEqualTo(SalaryPeriod.MONTH);

        NormalizedSalary withCurrencyLast = salary("We offer salary from 3033-3683 EUR gross per month.");
        assertThat(withCurrencyLast.min()).isEqualByComparingTo("3033");
        assertThat(withCurrencyLast.max()).isEqualByComparingTo("3683");
    }

    @Test
    void rangeWithToAndEnglishThousandsWithoutBasis() {
        NormalizedSalary found = salary("We offer salary from 36,400 to 44,200 EUR/annual. "
                + "Salary can be higher depending on experience and skillset.");
        assertThat(found.min()).isEqualByComparingTo("36400");
        assertThat(found.max()).isEqualByComparingTo("44200");
        assertThat(found.period()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(found.basis()).isEqualTo(SalaryBasis.UNKNOWN);
    }

    @Test
    void annualizedBasePayRangeWithDecimals() {
        NormalizedSalary found = salary("The potential base pay range for this role, when annualized, "
                + "is €32,200.00 - €48,400.00. The actual base pay offered may vary");
        assertThat(found.min()).isEqualByComparingTo("32200");
        assertThat(found.max()).isEqualByComparingTo("48400");
        assertThat(found.period()).isEqualTo(SalaryPeriod.YEAR);
    }

    @Test
    void slovakMonthlyMinimum() {
        NormalizedSalary found = salary("Mzdové podmienky (brutto) 2 000 EUR/mesiac Plus ročný bonus 15%. "
                + "Uvedená výška hrubej mesačnej mzdy je najnižšou hranicou.");
        assertThat(found.min()).isEqualByComparingTo("2000");
        assertThat(found.max()).isNull();
        assertThat(found.period()).isEqualTo(SalaryPeriod.MONTH);
        assertThat(found.basis()).isEqualTo(SalaryBasis.GROSS);
    }

    @Test
    void englishMinimumStatedAfterAmount() {
        NormalizedSalary found = salary("3 sick days EUR 1,700 gross per month Plus an annual bonus of up to 15%. "
                + "The stated gross monthly salary represents the minimum starting salary for this position.");
        assertThat(found.min()).isEqualByComparingTo("1700");
        assertThat(found.max()).isNull();
        assertThat(found.period()).isEqualTo(SalaryPeriod.MONTH);
    }

    // --- Форма: только max, точная сумма ---

    @Test
    void upToGivesMaxOnlyAndThousandsSuffix() {
        NormalizedSalary found = salary("Salary up to €60k per year, depending on experience.");
        assertThat(found.min()).isNull();
        assertThat(found.max()).isEqualByComparingTo("60000");
    }

    @Test
    void singleAmountWithoutQualifierIsExact() {
        NormalizedSalary found = salary("Gehalt: 55.000 EUR jährlich brutto.");
        assertThat(found.min()).isEqualByComparingTo("55000");
        assertThat(found.max()).isEqualByComparingTo("55000");
        assertThat(found.period()).isEqualTo(SalaryPeriod.YEAR);
    }

    // --- Не зарплата ---

    @Test
    void zeroTemplatesAndEmptyRangeAreAbsent() {
        assertThat(extractor.extract("The potential base pay range for this role, when annualized, "
                + "is $0.00 - $0.00. The actual base pay offered may vary").isPresent()).isFalse();
        assertThat(extractor.extract("The potential base pay range for this role, when annualized, "
                + "is 0,00 $ - 0,00 $.").isPresent()).isFalse();
        assertThat(extractor.extract("The potential base pay range for this role is "
                + "The actual base pay offered may vary").isPresent()).isFalse();
    }

    @Test
    void benefitsPercentagesAndRevenueAreNotSalary() {
        assertThat(extractor.extract("Annual Cafeteria benefit of CZK 7,000 for sports, learning, and leisure "
                + "activities Company contribution to pension savings (2–3% of salary)").isPresent()).isFalse();
        assertThat(extractor.extract("Fully covered meal vouchers (CZK 110 per day) Cafeteria system "
                + "(CZK 7,000 annually) Pension contribution (2–3% of salary)").isPresent()).isFalse();
        assertThat(extractor.extract("Hitachi has revenues of $10.2 billion and pays a competitive salary.")
                .isPresent()).isFalse();
    }

    @Test
    void numbersWithoutCurrencyOrWithoutSalaryWordAreIgnored() {
        assertThat(extractor.extract("Flexible working hours (7.5-hour workday) Date Posted: 2026-07-07")
                .isPresent()).isFalse();
        assertThat(extractor.extract("compensation packages that let you share in long-term success!")
                .isPresent()).isFalse();
        assertThat(extractor.extract(null)).isEqualTo(ExtractedSalary.ABSENT);
    }

    @Test
    void fragmentKeepsTheSourceWording() {
        ExtractedSalary extracted = extractor.extract("Für diese Position gilt das kollektivvertragliche "
                + "Mindestgehalt von 76 216 EUR p.a. brutto.");
        assertThat(extracted.fragment()).contains("Mindestgehalt von 76 216 EUR p.a. brutto");
    }

    @Test
    void amountParsingSeparators() {
        assertThat(SalaryTextExtractor.amount("3.348,62", null)).isEqualByComparingTo("3348.62");
        assertThat(SalaryTextExtractor.amount("32,200.00", null)).isEqualByComparingTo("32200");
        assertThat(SalaryTextExtractor.amount("75.000", null)).isEqualByComparingTo("75000");
        assertThat(SalaryTextExtractor.amount("36,400", null)).isEqualByComparingTo("36400");
        assertThat(SalaryTextExtractor.amount("76 216", null)).isEqualByComparingTo("76216");
        assertThat(SalaryTextExtractor.amount("60", "k")).isEqualByComparingTo(new BigDecimal("60000"));
    }
}
