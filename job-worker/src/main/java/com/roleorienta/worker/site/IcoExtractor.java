package com.roleorienta.worker.site;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IČO из текста страницы: восьмизначный номер после подписи «IČO» (или «ICO»), с двоеточием или
 * без, цифры могут быть разделены пробелами («IČO: 12 345 678»). Номер без подписи не берётся —
 * восемь цифр встречаются где угодно. Торговый кодекс Словакии (§3a) обязывает предпринимателя
 * указывать на сайте данные о себе; обычно — на страницах «Kontakt», «O nás» или в подвале.
 */
final class IcoExtractor {

    private static final Pattern ICO = Pattern.compile(
            "(?iu)(?<![\\p{L}])I[ČC]O(?![\\p{L}])\\s*[:.]?\\s*((?:\\d[ \\u00A0]?){7}\\d)(?!\\d)");
    private static final int DIGITS = 8;

    private IcoExtractor() {
    }

    /**
     * @param text текст страницы
     * @return найденные IČO по порядку, без повторов
     */
    static Set<String> extract(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        Matcher matcher = ICO.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group(1).replaceAll("\\D", "");
            if (digits.length() == DIGITS) {
                numbers.add(digits);
            }
        }
        return numbers;
    }
}
