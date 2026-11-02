package com.roleorienta.worker.career;

import java.net.URI;
import java.util.Optional;
import org.jsoup.nodes.Document;

/**
 * Страница сайта после выполнения её скриптов (§236): меню, ссылки и списки, которые строит скрипт, — их нет в HTML,
 * который отдаёт сервер. Запасной путь проверки сайта: только когда в HTML ничего не нашлось.
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
}
