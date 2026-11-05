package com.roleorienta.worker.adapter.stateportal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Позиция каталога profesia.sk, которую работодатель указал в объявлении (§267): строка «Pozícia:» страницы объявления
 * — ссылки {@code /praca/<ключ>/} с названием («Pozícia: Predavač/predavačka, Pokladník/pokladníčka»). Ключ — часть
 * адреса позиции на profesia.sk ({@code predavac-predavacka}); у английского и словацкого объявления ключи одной позиции
 * могут различаться — каждый сопоставляется группе ISCO-08 отдельно.
 *
 * @param slug ключ позиции (адрес {@code /praca/<ключ>/})
 * @param name название позиции, как на странице
 */
public record ProfesiaPosition(String slug, String name) {

    /** Подпись строки позиций (словацкий интерфейс profesia.sk — и у объявлений на английском). */
    private static final Pattern LABEL = Pattern.compile("^(pozícia|pozicia|position)\\s*:?$");
    private static final Pattern POSITION_PATH = Pattern.compile("^/praca/([a-z0-9-]+)/$");
    /** Длина {@code profesia_position.slug} и {@code name}. */
    private static final int MAX_SLUG = 200;
    private static final int MAX_NAME = 300;

    /**
     * @param page страница объявления profesia.sk
     * @return позиции объявления по порядку; строки позиций нет — пусто
     */
    public static List<ProfesiaPosition> of(Document page) {
        List<ProfesiaPosition> positions = new ArrayList<>();
        for (Element label : page.select("strong")) {
            if (!LABEL.matcher(label.text().strip().toLowerCase(Locale.ROOT)).matches()) {
                continue;
            }
            for (Element next = label.nextElementSibling(); next != null && !"strong".equals(next.tagName());
                    next = next.nextElementSibling()) {
                Matcher path = POSITION_PATH.matcher(next.attr("href"));
                String name = next.text().strip();
                if ("a".equals(next.tagName()) && path.matches() && !name.isEmpty()
                        && path.group(1).length() <= MAX_SLUG) {
                    positions.add(new ProfesiaPosition(path.group(1),
                            name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME)));
                }
            }
            return positions;
        }
        return positions;
    }
}
