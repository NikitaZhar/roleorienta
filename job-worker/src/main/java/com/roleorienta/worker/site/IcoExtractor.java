package com.roleorienta.worker.site;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IČO из текста страницы: номер из 5–8 цифр после подписи — «IČO», «I.Č.O.», «IČ», «ICO», «Company ID»,
 * «Reg. No.», «Registration No.» (с двоеточием или без), цифры могут быть разделены пробелами
 * («IČO: 12 345 678»). Короткий номер дополняется ведущими нулями до восьми цифр («IČO: 684881» → 00684881).
 * Номер без подписи не берётся — цифры встречаются где угодно. Правило — как в скрипте замера
 * {@code survey/site-search.py} (стенограмма §73); подпись внутри слова («Mexico») не считается. Торговый
 * кодекс Словакии (§3a) обязывает предпринимателя указывать на сайте данные о себе; обычно — на страницах
 * «Kontakt», «O nás» или в подвале.
 */
final class IcoExtractor {

    private static final Pattern ICO = Pattern.compile("(?iu)(?<![\\p{L}])"
            + "(?:I\\.?\\s?Č\\.?\\s?O\\.?|I\\.?\\s?Č\\.?|ICO|IČ|company\\s+id|reg(?:istration)?\\.?\\s*no\\.?)"
            + "\\s*[:.]?\\s*((?:\\d[\\s\\u00A0]?){5,8})(?!\\d)");
    private static final int DIGITS = 8;

    private IcoExtractor() {
    }

    /**
     * @param text текст страницы
     * @return найденные IČO (восемь цифр) по порядку, без повторов
     */
    static Set<String> extract(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        Matcher matcher = ICO.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group(1).replaceAll("\\D", "");
            numbers.add("0".repeat(DIGITS - digits.length()) + digits);
        }
        return numbers;
    }
}
