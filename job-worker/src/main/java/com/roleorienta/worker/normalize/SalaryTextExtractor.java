package com.roleorienta.worker.normalize;

import com.roleorienta.core.domain.SalaryBasis;
import com.roleorienta.core.domain.SalaryPeriod;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Извлечение зарплаты из текста описания правилами (§6, A09; ADR-13 — без ML).
 *
 * <p><b>Зачем.</b> У Workday нет структурного поля зарплаты, а в SK/AT сумма почти всегда
 * есть в тексте: в Австрии объявление обязано называть минимальный оклад по
 * коллективному договору, в Словакии — базовую зарплату. Формулировки проверены на
 * живых вакансиях 2026-09-24 (Hitachi, DXC, IQVIA; §66): «Mindestgehalt … 3.775,25 € in
 * Vollzeit», «EUR 3.348,62 brutto pro Monat», «starts at € 60.000 gross p.a.»,
 * «76 216 EUR p.a. brutto», «EUR 3000 – 4500 gross per month», «from 36,400 to 44,200
 * EUR/annual», «when annualized, is €32,200.00 - €48,400.00», «2 000 EUR/mesiac».</p>
 *
 * <p><b>Как.</b> Ищется денежное выражение: сумма (европейские и английские разделители:
 * {@code 3.348,62}, {@code 76 216}, {@code 32,200.00}) с валютой до или после, либо
 * диапазон двух сумм через {@code - – to bis až}. Кандидат принимается, только если:</p>
 * <ul>
 *   <li>рядом (150 символов до, 80 после) есть слово о зарплате — {@code salary, Gehalt,
 *       Vergütung, pay, compensation, gross, brutto, mzda …};</li>
 *   <li>непосредственно перед суммой (40 символов) нет слов о льготах —
 *       {@code Essenszuschuss, meal, voucher, cafeteria, bonus …} («8 € Essenszuschuss» — не
 *       зарплата), а сразу после — {@code %, billion, Mio …} (обороты компаний);</li>
 *   <li>сумма не нулевая (у IQVIA встречается «$0.00 - $0.00» — шаблон без данных).</li>
 * </ul>
 * <p>Берётся первый прошедший кандидат по тексту.</p>
 *
 * <p><b>Честность (A09).</b> Период и база ставятся только по явным словам рядом с суммой
 * ({@code p.a., per month, pro Monat, /mesiac …}; {@code gross, brutto, hrubá …}); иначе —
 * {@code UNKNOWN}: «3.775,25 € in Vollzeit» без слова «Monat» — период неизвестен, хотя
 * по смыслу это месячный оклад (догадки не делаются). Форма: диапазон → min и max; одна
 * сумма со словами «from / ab / starts at / startet bei / Mindest- / minimum / od» (до 150
 * символов перед ней) или «can be higher / Überzahlung / najnižšou» (до 120 после) → только
 * min; «up to / bis zu» прямо перед суммой → только max; иначе — точная сумма (min = max).</p>
 *
 * <p>Словари малы (пилот) и ведутся как данные; качество ограничено их полнотой (ADR-13).
 * Межвалютный/межпериодный пересчёт — не здесь.</p>
 */
@Component
public class SalaryTextExtractor {

    /** Валюта: длинные обозначения раньше коротких ({@code US$} раньше {@code $}). */
    private static final String CURRENCY = "US\\$|USD|EUR|€|CZK|Kč|CHF|GBP|£|\\$";

    /**
     * Сумма: с разделителями тысяч (точка, запятая, пробел, неразрывный пробел) и
     * необязательной дробной частью из 1–2 цифр ({@code 3.775, 25} — встречается с
     * пробелом после запятой), либо цифры подряд ({@code 3000}, {@code 3.000} тоже попадёт
     * в первую ветку).
     */
    private static final String NUMBER =
            "\\d{1,3}(?:[.,\\u00a0 ]\\d{3})+(?:[.,]\\s?\\d{1,2}(?!\\d))?|\\d+(?:[.,]\\d{1,2}(?!\\d))?";

    /** Одна денежная величина: [валюта] сумма [k] [валюта]. */
    private static String money(int index) {
        return "(?:(?<curA" + index + ">" + CURRENCY + ")\\s?)?"
                + "(?<num" + index + ">" + NUMBER + ")"
                + "(?<k" + index + ">[kK](?!\\p{L}))?"
                + "(?:\\s?(?<curB" + index + ">" + CURRENCY + "))?";
    }

    /** Денежное выражение: величина или диапазон двух величин. */
    private static final Pattern EXPRESSION = Pattern.compile(
            money(1) + "(?:\\s?(?:-|–|—|to|bis|až)\\s?" + money(2) + ")?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Слова о зарплате (хотя бы одно — рядом с суммой). */
    private static final Pattern ANCHOR = Pattern.compile(
            "salar|gehalt|vergütung|entlohnung|\\blohn|bezahlung|\\bpay\\b|compensation|remuneration"
                    + "|gross|brutto|\\bmzd|\\bmzda|\\bplat\\b|hrub");

    /** Льготы и прочие суммы, не являющиеся зарплатой (прямо перед суммой). */
    private static final Pattern EXCLUDE_BEFORE = Pattern.compile(
            "zuschuss|essen|meal|voucher|allowance|cafeteria|benefit|bonus|prämie|príspev|stravn"
                    + "|pension|revenue|umsatz|turnover|funding|investment|budget");

    /** Проценты и обороты (прямо после суммы). */
    private static final Pattern EXCLUDE_AFTER = Pattern.compile(
            "^\\s?(?:%|billion|million|mrd|mio\\b|bn\\b|mld)");

    private static final Pattern YEAR = Pattern.compile(
            "p\\.\\s?a\\.|per annum|annuali[sz]ed|\\bannual(?!\\s+(?:bonus|leave|cafeteria|benefit))|\\bannually"
                    + "|per year|a year|/\\s?year|jährlich|pro jahr|jahres(?:gehalt|brutto)|\\bročne|/\\s?rok\\b|za rok");
    private static final Pattern MONTH = Pattern.compile(
            "per month|a month|/\\s?month|\\bmonthly|pro monat|monatlich|monats(?:gehalt|brutto)"
                    + "|mesačn|/\\s?mesiac|za mesiac");
    private static final Pattern WEEK = Pattern.compile("per week|/\\s?week|\\bweekly|pro woche|wöchentlich");
    private static final Pattern DAY = Pattern.compile("per day|/\\s?day\\b|\\bdaily|pro tag\\b|täglich");
    private static final Pattern HOUR = Pattern.compile(
            "per hour|/\\s?h(?:our|r)?\\b|\\bhourly|pro stunde|stündlich|/\\s?hod");

    private static final Pattern GROSS = Pattern.compile("gross|brutto|\\bhrub");
    private static final Pattern NET = Pattern.compile("netto|\\bnet\\b|\\bčist");

    /** «Не меньше»: перед суммой или «может быть выше» после неё. */
    private static final Pattern MIN_ONLY = Pattern.compile(
            "\\bfrom\\b|\\bab\\b|\\bstart(?:s|ing|et)?\\b|beginnt|\\bmin(?:imum|imal|\\.)?\\b|mindest|at least|\\bod\\b"
                    + "|najnižš|minimáln|übersteig|übertr|überzahlung|overpay|exceed|can be higher|may be higher");
    /** «Не больше»: только прямо перед суммой. */
    private static final Pattern MAX_ONLY = Pattern.compile(
            "up to|bis zu|\\bmax(?:imum|\\.)?\\b|höchstens|\\bdo\\b");

    /** Окно поиска слова о зарплате: символов до и после денежного выражения. */
    private static final int ANCHOR_BEFORE = 150;
    private static final int ANCHOR_AFTER = 80;
    /** Окна исключений: льготы — прямо перед суммой, проценты/обороты — сразу после. */
    private static final int EXCLUDE_BEFORE_WINDOW = 40;
    private static final int EXCLUDE_AFTER_WINDOW = 15;
    /** Окна поиска периода и базы. */
    private static final int PERIOD_BEFORE = 60;
    private static final int PERIOD_AFTER = 40;
    /** Окна слов формы: «не меньше» — до/после суммы, «не больше» — прямо перед ней. */
    private static final int MIN_BEFORE = 150;
    private static final int MIN_AFTER = 120;
    private static final int MAX_BEFORE = 25;
    /** Фрагмент текста, сохраняемый как сырое значение. */
    private static final int FRAGMENT_BEFORE = 80;
    private static final int FRAGMENT_AFTER = 40;
    /** Множитель суффикса «k» ({@code €60k}). */
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    /**
     * Ищет зарплату в тексте описания.
     *
     * @param description текст описания или {@code null}
     * @return найденная зарплата с фрагментом текста, либо {@link ExtractedSalary#ABSENT}
     *         (текст пуст, денежных выражений нет или ни одно не прошло правила)
     */
    public ExtractedSalary extract(String description) {
        if (description == null || description.isBlank()) {
            return ExtractedSalary.ABSENT;
        }
        String lower = description.toLowerCase(Locale.ROOT);
        Matcher expression = EXPRESSION.matcher(description);
        while (expression.find()) {
            ExtractedSalary candidate = candidate(description, lower, expression);
            if (candidate != null) {
                return candidate;
            }
        }
        return ExtractedSalary.ABSENT;
    }

    /** Проверяет одно денежное выражение по правилам класса; {@code null} — не зарплата. */
    private ExtractedSalary candidate(String text, String lower, Matcher expression) {
        String currency = currency(expression.group("curA1"), expression.group("curB1"),
                expression.group("curA2"), expression.group("curB2"));
        if (currency == null) {
            return null;                                    // число без валюты — не деньги
        }
        int start = expression.start();
        int end = expression.end();
        if (EXCLUDE_BEFORE.matcher(window(lower, start - EXCLUDE_BEFORE_WINDOW, start)).find()
                || EXCLUDE_AFTER.matcher(window(lower, end, end + EXCLUDE_AFTER_WINDOW)).find()
                || !ANCHOR.matcher(window(lower, start - ANCHOR_BEFORE, end + ANCHOR_AFTER)).find()) {
            return null;
        }
        BigDecimal first = amount(expression.group("num1"), expression.group("k1"));
        BigDecimal second = expression.group("num2") == null
                ? null : amount(expression.group("num2"), expression.group("k2"));
        if (first == null || first.signum() == 0 || (second != null && second.signum() == 0)) {
            return null;                                    // «$0.00 - $0.00» — шаблон без данных
        }
        Bounds bounds = bounds(lower, start, end, first, second);
        NormalizedSalary salary = new NormalizedSalary(bounds.min(), bounds.max(), currency,
                period(lower, start, end), basis(lower, start, end));
        return new ExtractedSalary(salary, fragment(text, start, end));
    }

    /**
     * Форма суммы: диапазон → min и max; одна сумма со словами «не меньше» → только min;
     * «не больше» прямо перед суммой → только max; иначе — точная (min = max).
     *
     * @return границы; любая может быть {@code null}
     */
    private Bounds bounds(String lower, int start, int end, BigDecimal first, BigDecimal second) {
        if (second != null) {
            return new Bounds(first.min(second), first.max(second));
        }
        if (MIN_ONLY.matcher(window(lower, start - MIN_BEFORE, start)).find()
                || MIN_ONLY.matcher(window(lower, end, end + MIN_AFTER)).find()) {
            return new Bounds(first, null);
        }
        if (MAX_ONLY.matcher(window(lower, start - MAX_BEFORE, start)).find()) {
            return new Bounds(null, first);
        }
        return new Bounds(first, first);
    }

    /**
     * Период: ближайшее явное слово после суммы (до 40 символов), иначе ближайшее перед
     * ней (до 60). Нет слова — {@code UNKNOWN}.
     */
    private SalaryPeriod period(String lower, int start, int end) {
        String after = window(lower, end, end + PERIOD_AFTER);
        SalaryPeriod best = SalaryPeriod.UNKNOWN;
        int bestPosition = Integer.MAX_VALUE;
        for (SalaryPeriod period : SalaryPeriod.values()) {
            Pattern cue = cue(period);
            if (cue != null) {
                Matcher cueMatcher = cue.matcher(after);
                if (cueMatcher.find() && cueMatcher.start() < bestPosition) {
                    bestPosition = cueMatcher.start();
                    best = period;
                }
            }
        }
        if (best != SalaryPeriod.UNKNOWN) {
            return best;
        }
        String before = window(lower, start - PERIOD_BEFORE, start);
        int lastPosition = -1;
        for (SalaryPeriod period : SalaryPeriod.values()) {
            Pattern cue = cue(period);
            if (cue != null) {
                Matcher cueMatcher = cue.matcher(before);
                while (cueMatcher.find()) {
                    if (cueMatcher.start() > lastPosition) {
                        lastPosition = cueMatcher.start();
                        best = period;
                    }
                }
            }
        }
        return best;
    }

    /** Слова-признаки периода; для {@code UNKNOWN} — {@code null}. */
    private static Pattern cue(SalaryPeriod period) {
        return switch (period) {
            case YEAR -> YEAR;
            case MONTH -> MONTH;
            case WEEK -> WEEK;
            case DAY -> DAY;
            case HOUR -> HOUR;
            case UNKNOWN -> null;
        };
    }

    /** База: gross/net по явным словам рядом; оба сразу или ни одного — {@code UNKNOWN}. */
    private SalaryBasis basis(String lower, int start, int end) {
        String around = window(lower, start - PERIOD_BEFORE, end + PERIOD_AFTER);
        boolean gross = GROSS.matcher(around).find();
        boolean net = NET.matcher(around).find();
        if (gross == net) {
            return SalaryBasis.UNKNOWN;
        }
        return gross ? SalaryBasis.GROSS : SalaryBasis.NET;
    }

    /** Первая указанная валюта выражения в виде ISO-кода, либо {@code null}. */
    private static String currency(String... symbols) {
        for (String symbol : symbols) {
            if (symbol != null) {
                return switch (symbol.toUpperCase(Locale.ROOT)) {
                    case "€", "EUR" -> "EUR";
                    case "$", "US$", "USD" -> "USD";
                    case "£", "GBP" -> "GBP";
                    case "KČ", "CZK" -> "CZK";
                    case "CHF" -> "CHF";
                    default -> null;
                };
            }
        }
        return null;
    }

    /**
     * Сумма из строки с разделителями: если последний разделитель отделяет 1–2 цифры в
     * конце — это дробная часть ({@code 3.348,62}, {@code 32,200.00}), остальные
     * разделители — тысячи; иначе все разделители — тысячи ({@code 75.000}, {@code 36,400}).
     *
     * @param raw       сумма как в тексте (с разделителями и пробелами)
     * @param thousands суффикс «k» или {@code null}
     * @return сумма или {@code null}, если строка не разбирается как число
     */
    static BigDecimal amount(String raw, String thousands) {
        String compact = raw.replaceAll("[\\s\\u00a0]", "");
        int last = Math.max(compact.lastIndexOf('.'), compact.lastIndexOf(','));
        String normalized;
        if (last >= 0 && compact.length() - last - 1 <= 2) {
            normalized = compact.substring(0, last).replaceAll("[.,]", "") + "." + compact.substring(last + 1);
        } else {
            normalized = compact.replaceAll("[.,]", "");
        }
        try {
            BigDecimal value = new BigDecimal(normalized);
            return thousands == null ? value : value.multiply(THOUSAND);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Фрагмент текста вокруг суммы, обрезанный по пробелам. */
    private static String fragment(String text, int start, int end) {
        int from = Math.max(0, start - FRAGMENT_BEFORE);
        int to = Math.min(text.length(), end + FRAGMENT_AFTER);
        if (from > 0) {
            int space = text.indexOf(' ', from);
            from = space >= 0 && space < start ? space + 1 : from;
        }
        if (to < text.length()) {
            int space = text.lastIndexOf(' ', to);
            to = space > end ? space : to;
        }
        return text.substring(from, to).strip();
    }

    /** Нижняя и верхняя граница суммы (любая может быть {@code null}). */
    private record Bounds(BigDecimal min, BigDecimal max) {
    }

    /** Подстрока с безопасными границами. */
    private static String window(String text, int from, int to) {
        return text.substring(Math.max(0, from), Math.min(text.length(), Math.max(0, to)));
    }
}
