package com.roleorienta.worker.browser;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;

/**
 * Кнопка «показать ещё» на странице списка вакансий (§241): список подгружается нажатием, а не ссылкой на следующую
 * страницу (AGEL: «Zobraziť viac» — в HTML сервера 7 из 196 вакансий). Кнопка — {@value #SELECTOR}: не ссылка на
 * другую страницу.
 */
public final class LoadMore {

    /** Элементы-кнопки: кнопка, роль кнопки, ссылка без адреса или на «#». */
    public static final String SELECTOR = "button, [role=button], a[href='#'], a:not([href])";

    /** Текст кнопки без диакритики, нижний регистр: словацкий, чешский, английский, немецкий. */
    public static final Pattern TEXT = Pattern.compile("^\\s*(zobrazit (viac|dalsie|vsetky|vice|dalsi)( ponuky| pozicie)?"
            + "|nacitat (viac|dalsie)|nacist dalsi|dalsie ponuky|viac (ponuk|pozicii)|show more|load more|more jobs"
            + "|view more|see more|mehr anzeigen|weitere (laden|anzeigen))\\s*$");

    private LoadMore() {
    }

    /**
     * @param page страница по HTML сервера
     * @return на странице есть кнопка «показать ещё»
     */
    public static boolean present(Document page) {
        return page.select(SELECTOR).stream().anyMatch(element -> TEXT.matcher(plain(element.text())).matches());
    }

    /**
     * @param text текст
     * @return без диакритики, в нижнем регистре
     */
    static String plain(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
