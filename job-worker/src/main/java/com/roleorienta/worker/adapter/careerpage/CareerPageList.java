package com.roleorienta.worker.adapter.careerpage;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Разбор собственной кадровой страницы компании — HTML-списка вакансий без системы найма и без разметки
 * {@code JobPosting} (технический документ §5, «собственная страница вакансий»; §136). Правила (замер 2026-10-19 на
 * кадровых страницах «формат не поддерживается»):
 *
 * <ul>
 *   <li>Вакансия — ссылка того же регистрируемого домена ({@code kariera.firma.sk} у {@code www.firma.sk}), вне
 *       {@code header}, {@code nav}, {@code footer}; не файл ({@code .pdf} …), не сама страница; текст — от 1 до
 *       {@value #MAX_TITLE_WORDS} слов и не пункт меню ({@link #NOT_TITLE}).</li>
 *   <li>Ссылки группируются по родительскому пути ({@code /kariera/casnik} → {@code /kariera/}); корень и пути
 *       страниц списка ({@code /page/2/}) не в счёт. Список — самая большая группа с кадровым словом в родительском
 *       пути ({@link #CAREER_PATH}; §183: без этого условия самой большой группой часто оказывались товары, меню и
 *       новости сайта).</li>
 *   <li>Список подтверждается текстом ({@link #confirmed}, §183): из первых {@value #VACANCY_SAMPLE} страниц вакансий
 *       хотя бы половина открывшихся — с признаками текста вакансии ({@link #JOB_TEXT}). Проверку делают те, кто
 *       открывает страницы: чтение источника и проверка сайта.</li>
 *   <li>Место — текст карточки вакансии без текста ссылок (название, кнопки): самый большой родитель ссылки, в
 *       котором нет других вакансий и не больше {@value #MAX_CARD} символов.</li>
 *   <li>Следующая страница списка — {@code rel="next"} или ссылка «Ďalej», «Ďalšia», «Next», «»».</li>
 *   <li>Фраза «вакансий нет» ({@link #NO_VACANCIES}) — пустой список полный.</li>
 * </ul>
 */
public final class CareerPageList {

    /** Слов в названии вакансии не больше. */
    static final int MAX_TITLE_WORDS = 15;

    /** Сколько первых страниц вакансий списка открывается для подтверждения текстом (§183). */
    static final int VACANCY_SAMPLE = 3;

    /** Символов в тексте карточки вакансии не больше. */
    static final int MAX_CARD = 400;

    /** Сколько первых символов текста страницы проверяется на фразу «вакансий нет». */
    private static final int TEXT_LIMIT = 300_000;

    private static final Set<String> SCHEMES = Set.of("http", "https");
    private static final Set<String> LAYOUT_TAGS = Set.of("header", "nav", "footer");
    private static final Set<String> CARD_TAGS = Set.of("li", "tr", "article", "div", "section", "p", "td");

    private static final Pattern FILE = Pattern.compile("(?i)\\.(pdf|docx?|xlsx?|jpe?g|png|gif|zip)$");
    private static final Pattern PAGINATION_PATH = Pattern.compile("(?i)/(page|strana|stranka)/\\d+/?$");

    /** Текст ссылки — пункт меню или кнопка, а не название вакансии (без диакритики, нижний регистр). */
    private static final Pattern NOT_TITLE = Pattern.compile("^(kariera|career|careers|jobs?|praca|pozicie|"
            + "volne (pracovne )?(pozicie|miesta)|pracovne (ponuky|pozicie|miesta)|ponuka prace|ponuky prace|"
            + "o nas|kontakt|kontakty|domov|home|viac|viac info|viac informacii|detail|detaily|zobrazit|"
            + "zobrazit viac|citat viac|read more|more|apply|uchadzat sa|prihlasit sa|reagovat|dalej|spat|back|"
            + "next|previous|predchadzajuca|dalsia|english|slovensky|en|sk|de|pracovne prilezitosti|\\d+)$");

    /** Кадровое слово в родительском пути: группа ссылок под ним может быть списком (§183: «karriere» — тоже). */
    private static final Pattern CAREER_PATH = Pattern.compile(
            "(?i)kar{1,2}i[eé]r|career|job|pr[aá]c|pozic|ponuk|profesi|vacanc|stellen|nabor");

    /**
     * Признаки текста вакансии (без диакритики, нижний регистр; §183, замер {@code target/careerpage-nonvacancy.sql}):
     * требования, обязанности, условия, зарплата, начало работы — по-словацки, по-чешски, по-английски, по-немецки.
     */
    private static final Pattern JOB_TEXT = Pattern.compile("poziadavk|napln prace|ponukame|mzd[ay]|nastup|"
            + "pracovn[yeo] pomer|uvazok|zivotopis|requirement|responsibilit|qualification|we offer|salary|"
            + "your profile|job description|pozadavk|nabizime|anforderung|aufgaben|wir bieten|ihr profil");

    private static final Pattern NEXT_TEXT = Pattern.compile("^(dalej|dalsia|dalsie|next|»|›|>)\\s*(»|›|>)?$");

    /** «Вакансий нет» (без диакритики, нижний регистр). */
    private static final Pattern NO_VACANCIES = Pattern.compile(
            "(neponukame|nemame)( momentalne| v sucasnosti| aktualne| teraz)?( ziadne)?( volne)?( pracovne)?"
                    + " (pozicie|miesta|ponuky)|momentalne nie (su|je) (ziadn[ea] )?voln|ziadne volne (pracovne )?"
                    + "(pozicie|miesta)|no (open )?(positions|vacancies|openings)|currently no (open )?"
                    + "(positions|vacancies|openings)");

    private CareerPageList() {
    }

    /**
     * Вакансия списка.
     *
     * @param url   адрес страницы вакансии без якоря
     * @param title название — текст ссылки
     * @param card  текст карточки без текста ссылок (место и прочее); пусто — нет
     */
    public record Item(String url, String title, String card) {
    }

    /**
     * @param page страница с адресом ({@link Document#location()})
     * @return вакансии списка в порядке страницы; пусто — список не распознан
     */
    public static List<Item> vacancies(Document page) {
        URI base = uri(page.location());
        if (base == null) {
            return List.of();
        }
        Map<String, Map<String, Element>> groups = new LinkedHashMap<>();
        for (Element anchor : page.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            String title = anchor.text().strip();
            if (link == null || !candidate(anchor, link, base, title)) {
                continue;
            }
            String path = link.getPath().replaceAll("/+$", "");
            String parent = path.substring(0, path.lastIndexOf('/') + 1);
            if (parent.length() <= 1 || PAGINATION_PATH.matcher(link.getPath()).find()) {
                continue;
            }
            Map<String, Element> group = groups.computeIfAbsent(parent, key -> new LinkedHashMap<>());
            Element known = group.get(link.toString());
            if (known == null || known.text().strip().length() < title.length()) {
                group.put(link.toString(), anchor);
            }
        }
        Optional<Map.Entry<String, Map<String, Element>>> largest = groups.entrySet().stream()
                .filter(group -> CAREER_PATH.matcher(group.getKey()).find())
                .max(Comparator.comparingInt(group -> group.getValue().size()));
        if (largest.isEmpty()) {
            return List.of();
        }
        Map<String, Element> links = largest.get().getValue();
        List<Item> items = new ArrayList<>();
        links.forEach((url, anchor) -> {
            items.add(new Item(url, anchor.text().strip(), card(anchor, links)));
        });
        return items;
    }

    /**
     * Подтверждение списка текстом (§183): открываются первые {@value #VACANCY_SAMPLE} страницы вакансий; список
     * подтверждён, если хотя бы половина открывшихся — с признаками текста вакансии ({@link #JOB_TEXT}). Ни одна не
     * открылась или список пуст — не подтверждён.
     *
     * @param urls адреса вакансий списка в порядке страницы
     * @param open открывает страницу; пусто — не открылась
     * @return {@code true} — ссылки списка ведут на вакансии
     */
    public static boolean confirmed(List<String> urls, Function<URI, Optional<Document>> open) {
        List<Boolean> checks = urls.stream().limit(VACANCY_SAMPLE).map(CareerPageList::uri).filter(Objects::nonNull)
                .map(open).flatMap(Optional::stream).map(page -> looksLikeVacancy(page.text())).toList();
        long vacancies = checks.stream().filter(Boolean::booleanValue).count();
        return !checks.isEmpty() && 2 * vacancies >= checks.size();
    }

    /**
     * @param text видимый текст страницы
     * @return в начале текста ({@value #TEXT_LIMIT} символов) есть признак текста вакансии
     */
    static boolean looksLikeVacancy(String text) {
        return JOB_TEXT.matcher(plain(text.substring(0, Math.min(text.length(), TEXT_LIMIT)))).find();
    }

    /**
     * @param page    страница списка
     * @param visited уже открытые страницы списка
     * @return адрес следующей страницы списка на том же сайте; пусто — нет
     */
    public static Optional<URI> nextPage(Document page, Set<String> visited) {
        URI base = uri(page.location());
        if (base == null) {
            return Optional.empty();
        }
        for (Element anchor : page.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            boolean next = "next".equalsIgnoreCase(anchor.attr("rel"))
                    || NEXT_TEXT.matcher(plain(anchor.text())).matches();
            if (next && link != null && sameSite(base.getHost(), link.getHost())
                    && !visited.contains(link.toString())) {
                return Optional.of(link);
            }
        }
        return Optional.empty();
    }

    /**
     * @param page страница
     * @return в тексте страницы фраза «вакансий нет»
     */
    public static boolean saysNoVacancies(Document page) {
        String text = page.text();
        return NO_VACANCIES.matcher(plain(text.substring(0, Math.min(text.length(), TEXT_LIMIT)))).find();
    }

    /**
     * Ссылка может быть вакансией: тот же сайт, не меню, не файл, не сама страница, текст — название.
     */
    private static boolean candidate(Element anchor, URI link, URI base, String title) {
        String words = plain(title);
        int count = words.isEmpty() ? 0 : words.split(" ").length;
        return sameSite(base.getHost(), link.getHost()) && link.getPath() != null && !link.getPath().isEmpty()
                && !FILE.matcher(link.getPath()).find() && !link.toString().equals(withoutFragment(base))
                && count >= 1 && count <= MAX_TITLE_WORDS && !NOT_TITLE.matcher(words).matches()
                && anchor.parents().stream().noneMatch(parent -> LAYOUT_TAGS.contains(parent.tagName()));
    }

    /**
     * Текст карточки: самый большой родитель ссылки без других вакансий списка и не длиннее {@value #MAX_CARD} — без
     * текста ссылок (название, кнопки «Viac», «Reagovať»); карточки нет — пусто.
     */
    private static String card(Element anchor, Map<String, Element> links) {
        Element card = anchor;
        for (Element parent : anchor.parents()) {
            if (!CARD_TAGS.contains(parent.tagName()) || parent.text().length() > MAX_CARD
                    || links.values().stream().anyMatch(other -> other != anchor && other.parents().contains(parent))) {
                break;
            }
            card = parent;
        }
        if (card == anchor) {
            return "";
        }
        Element copy = card.clone();
        copy.select("a").remove();
        return copy.text().replaceAll("\\s+", " ").strip();
    }

    /**
     * Один регистрируемый домен (две последние части хоста): {@code kariera.firma.sk} и {@code www.firma.sk}.
     */
    static boolean sameSite(String host, String other) {
        return host != null && other != null && domain(host).equals(domain(other));
    }

    private static String domain(String host) {
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        return labels.length <= 2 ? String.join(".", labels)
                : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private static String plain(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }

    /**
     * @return http(s)-адрес с хостом без якоря; {@code null} — не такой адрес
     */
    static URI uri(String link) {
        try {
            URI uri = new URI(link.replace(" ", "%20"));
            if (uri.getScheme() == null || !SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null) {
                return null;
            }
            return new URI(withoutFragment(uri));
        } catch (URISyntaxException malformed) {
            return null;
        }
    }

    private static String withoutFragment(URI uri) {
        String text = uri.toString();
        int fragment = text.indexOf('#');
        return fragment < 0 ? text : text.substring(0, fragment);
    }
}
