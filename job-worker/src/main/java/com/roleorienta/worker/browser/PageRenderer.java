package com.roleorienta.worker.browser;

import java.net.URI;
import java.util.Optional;
import org.jsoup.nodes.Document;

/**
 * Страница сайта после выполнения её скриптов (§236, §241): меню, ссылки и списки, которые строит скрипт, — их нет в
 * HTML, который отдаёт сервер. Запасной путь проверки сайта и чтения кадровой страницы: только когда в HTML ничего не
 * нашлось или список подгружается кнопкой «показать ещё».
 */
public interface PageRenderer {

    /** Без браузера: ничего не открывает (тесты, выключенный браузер). */
    PageRenderer NONE = uri -> Optional.empty();

    /**
     * @param uri адрес страницы
     * @return страница после выполнения скриптов с конечным адресом (после переадресаций, в том числе скриптом);
     *         пусто — не открылась, ошибка браузера или браузер выключен
     */
    Optional<Document> render(URI uri);

    /**
     * Страница списка целиком (§241): после выполнения скриптов нажимается «показать ещё» ({@link LoadMore}), пока
     * появляются новые ссылки, — не больше {@link BrowserProperties#expandClicks()} раз.
     *
     * @param uri адрес страницы списка
     * @return построенная страница со всеми подгруженными ссылками; пусто — как у {@link #render}
     */
    default Optional<Document> expanded(URI uri) {
        return render(uri);
    }
}
