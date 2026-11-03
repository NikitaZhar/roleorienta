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
 *       {@code nav}, {@code footer} и шапки сайта (шапка {@code header} внутри {@code article}/{@code main} — шапка
 *       карточки, §255); не файл ({@code .pdf} …), не сама страница; название ({@link #title(Element)}) — от 1 до
 *       {@value #MAX_TITLE_WORDS} слов и не пункт меню ({@link #NOT_TITLE}).</li>
 *   <li>Название — заголовок внутри ссылки ({@link #TITLE_SELECTORS}); у кнопки «подробнее» — заголовок её карточки;
 *       иначе текст ссылки без номера карточки впереди ({@link #LEADING_COUNTER}; «2 x Operátor» не трогается) (§252).</li>
 *   <li>Ссылки группируются по родительскому пути ({@code /kariera/casnik} → {@code /kariera/}); корень и пути
 *       страниц списка ({@code /page/2/}) не в счёт; вакансии корня сайта — только на кадровом хосте с номером в адресе
 *       ({@link #ROOT_VACANCY}); ссылки той же страницы с запросом ({@code /kariera?id=1}) — группа самой страницы, а при
 *       страницах вакансий под ней — отбор и сортировка (§255, §257); группа раздела не вакансий ({@link #NOT_JOBS_SEGMENT}:
 *       события, статьи, «почему мы») не в счёт. Список — самая большая группа с кадровым словом
 *       ({@link #CAREER_PATH}) в родительском пути или в хосте ссылок ({@code careers.firma.com}; §183: без этого
 *       условия самой большой группой часто оказывались товары, меню и новости сайта; §186: только путь терял
 *       отдельные кадровые сайты).</li>
 *   <li>Список подтверждается текстом ({@link #confirmed}, §183, §186): из первых {@value #VACANCY_SAMPLE} страниц
 *       вакансий хотя бы одна открывшаяся — с признаком текста вакансии ({@link #JOB_TEXT}) в основном тексте
 *       ({@code main}, иначе {@code body} без {@code header}, {@code nav}, {@code footer}). Проверку делают те, кто
 *       открывает страницы: чтение источника и проверка сайта.</li>
 *   <li>Место — текст карточки вакансии без текста ссылок и заголовка (название, кнопки): самый большой родитель
 *       ссылки, в котором нет других вакансий и не больше {@value #MAX_CARD} символов.</li>
 *   <li>Следующая страница списка — {@code rel="next"}, ссылка «Ďalej», «Ďalšia», «Next», «»» или номер следующей
 *       страницы (§255). «Далее» словом, ведущая на ту же страницу, якорь или скрипт, — листает скрипт
 *       ({@link #scriptNext}); стрелки каруселей и выключенные ссылки — нет (§257).</li>
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

    /** На сколько уровней вверх от ссылки-кнопки ищется заголовок карточки (§252). */
    private static final int CARD_LEVELS = 3;

    /** Сколько первых символов текста страницы проверяется на фразу «вакансий нет». */
    private static final int TEXT_LIMIT = 300_000;

    private static final Set<String> SCHEMES = Set.of("http", "https");
    private static final Set<String> LAYOUT_TAGS = Set.of("header", "nav", "footer");
    /** Внутри них {@code header} и {@code footer} — карточки, а не сайта (§255). */
    private static final Set<String> CARD_CONTAINERS = Set.of("article", "main");
    private static final Set<String> CARD_TAGS = Set.of("li", "tr", "article", "div", "section", "p", "td");

    private static final Pattern FILE = Pattern.compile("(?i)\\.(pdf|docx?|xlsx?|jpe?g|png|gif|zip)$");
    private static final Pattern PAGINATION_PATH = Pattern.compile("(?i)/(page|strana|stranka)/\\d+/?$");
    /** Последняя часть адреса — сам список вакансий, а не вакансия (§255). */
    private static final Pattern LIST_SLUG = Pattern.compile("(?i)ponuka-prace|ponuky-prace|volne-pozicie"
            + "|volne-pracovne-(?:pozicie|miesta)|pracovne-(?:ponuky|pozicie|miesta)|kariera|careers?|jobs?|praca|pozicie"
            + "|open-positions|vacancies|stellenangebote");

    /** Номер страницы списка в адресе: {@code ?strana=2}, {@code &page=2}, {@code /page/2/} (§255). */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?i)(?:[?&](?:strana|stranka|page|p|pg)=|/(?:page|strana|stranka)/)(\\d{1,4})(?=$|[&/#])");

    /** Текст ссылки — пункт меню или кнопка, а не название вакансии (без диакритики, нижний регистр). */
    private static final Pattern NOT_TITLE = Pattern.compile("^(kariera|career|careers|jobs?|praca|pozicie|"
            + "volne (pracovne )?(pozicie|miesta)|pracovne (ponuky|pozicie|miesta)|ponuka prace|ponuky prace|"
            + "o nas|kontakt|kontakty|domov|home|viac|viac info|viac informacii|detail|detaily|zobrazit|"
            + "zobrazit viac|citat viac|read more|more|apply|uchadzat sa|prihlasit sa|reagovat|dalej|spat|back|"
            + "next|previous|predchadzajuca|dalsia|english|slovensky|en|sk|de|pracovne prilezitosti|\\d+|"
            + "citat cely clanok|cely clanok|viac o nas|dozvediet sa viac|learn more|zistit viac|"
            + "(zobrazit|pozriet|otvorit|citat|zistit)( \\S+){0,3}|"
            + "(show|view|read|see) (more|details?|job|position|offer)( \\S+){0,2}|"
            + "(detail|detaily|viac)( \\S+){0,2} (ponuky|pozicie|pracovnej ponuky|informacii|info))$");

    /**
     * Последняя часть родительского пути — раздел не вакансий (§252, эталон §247): события, статьи, новости, страницы
     * «почему мы» на кадровом сайте (Kaufland {@code /events2/}, T-Systems {@code /careers/why-t-systems/}) — кадровое
     * слово в хосте их больше не делает списком. Сравнивается вся часть пути, а не подстрока (аудит §257:
     * {@code /o-nas/kariera/}, {@code /kariera/agriculture/} — списки).
     */
    private static final Pattern NOT_JOBS_SEGMENT = Pattern.compile("(?i)events?\\d*|podujatia|clanky?|clanok|blog"
            + "|news|novinky|aktuality|magazin|why-.*|culture|diversity|benefity?|benefits|o-nas|about(?:-us)?"
            + "|pribehy?|stories|tim|team|spoznaj-.*|zivot-v.*|life-at.*|faq|otazky|casto-kladene-otazky");

    /** Название в карточке по порядку предпочтения: заголовок, затем элемент с «title» или «position» в классе (§252). */
    private static final List<String> TITLE_SELECTORS = List.of("h1, h2, h3, h4, h5, h6",
            "[class*=title], [class*=Title]", "[class*=position], [class*=Position]");

    /** Вакансия в корне кадрового хоста — с номером в адресе ({@code /operator-vyroby-11196/}), не пункт меню. */
    private static final Pattern ROOT_VACANCY = Pattern.compile("\\d{3,}");

    /** Номер карточки перед названием («10 Poradca …», Tatra banka, §252). */
    private static final Pattern LEADING_COUNTER = Pattern.compile("^\\d{1,3}\\s+(?![xX×]\\s)(?=\\p{L})");

    /** Родитель кнопки длиннее — уже не карточка: заголовок вакансии в нем не ищется (§252). */
    private static final int MAX_TITLE_CARD = 2 * 400;

    /**
     * Кадровое слово в родительском пути или хосте: группа ссылок под ним может быть списком (§183: «karriere» —
     * тоже; §186: хост — тоже).
     */
    private static final Pattern CAREER_PATH = Pattern.compile(
            "(?i)kar{1,2}i[eé]r|career|job|pr[aá]c|pozic|ponuk|profesi|vacanc|stellen|nabor");

    /**
     * Признаки текста вакансии (без диакритики, нижний регистр; §183, замер {@code target/careerpage-nonvacancy.sql}):
     * требования, обязанности, условия, зарплата, начало работы — по-словацки, по-чешски, по-английски, по-немецки.
     */
    private static final Pattern JOB_TEXT = Pattern.compile("poziadavk|napln prace|ponukame|mzd[ay]|nastup|"
            + "pracovn[yeo] pomer|uvazok|zivotopis|requirement|responsibilit|qualification|we offer|salary|"
            + "your profile|job description|pozadavk|nabizime|anforderung|aufgaben|wir bieten|ihr profil");

    /** «Далее» словом, а не стрелкой: стрелки «›» бывают у каруселей (аудит §257) — для {@link #scriptNext}. */
    private static final Pattern NEXT_WORD = Pattern.compile(
            "^(dalej|dalsia|dalsie|next|dalsia strana|nasledujuca( strana)?|next page)\\s*(»|›|>)?$");

    private static final Pattern NEXT_TEXT = Pattern.compile(
            "^(dalej|dalsia|dalsie|next|»|›|>|dalsia strana|nasledujuca( strana)?|next page)\\s*(»|›|>)?$");

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
     * @param title название ({@link #title(Element)}: заголовок в ссылке или в карточке, иначе текст ссылки)
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
        boolean careerHost = CAREER_PATH.matcher(base.getHost()).find();
        String basePath = base.getPath().replaceAll("/+$", "");
        // Ссылки отбора на саму страницу отбрасываются, только когда у списка есть страницы вакансий под ней (Dr.Max:
        // …/volne-pozicie/detail-kariery?id=); иначе вакансии и есть ссылки с запросом ({@code /kariera?id=1}, аудит §257).
        boolean detailPages = page.select("a[href]").stream().map(anchor -> uri(anchor.absUrl("href")))
                .anyMatch(link -> link != null && sameSite(base.getHost(), link.getHost())
                        && link.getPath() != null && link.getPath().startsWith(basePath + "/"));
        for (Element anchor : page.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            String title = title(anchor);
            if (link == null || !candidate(anchor, link, base, title)) {
                continue;
            }
            String path = link.getPath().replaceAll("/+$", "");
            // Та же страница с другим запросом — отбор, сортировка, «в избранное» (Dr.Max, §255), не вакансия; ссылка
            // на сам список («…/ponuka-prace/» с главной кадрового сайта AGEL) — тоже.
            boolean samePage = path.equals(basePath) && link.getQuery() != null;
            if (samePage && detailPages
                    || !samePage && LIST_SLUG.matcher(path.substring(path.lastIndexOf('/') + 1)).matches()) {
                continue;
            }
            // Вакансии — сама страница с запросом ({@code /kariera?id=1}): группа — путь страницы, а не корень сайта.
            String parent = samePage ? path + "/" : path.substring(0, path.lastIndexOf('/') + 1);
            // Ссылки вакансий в корне сайта — только на кадровом хосте (§252: ponukyprace.manuvia.com/<вакансия>/).
            if (parent.length() <= 1 && !(careerHost && ROOT_VACANCY.matcher(path).find()) || path.isEmpty()
                    || PAGINATION_PATH.matcher(link.getPath()).find() || notJobs(parent)) {
                continue;
            }
            Map<String, Element> group = groups.computeIfAbsent(parent, key -> new LinkedHashMap<>());
            Element known = group.get(link.toString());
            if (known == null || title(known).length() < title.length()) {
                group.put(link.toString(), anchor);
            }
        }
        Optional<Map.Entry<String, Map<String, Element>>> largest = groups.entrySet().stream()
                .filter(group -> underCareer(group.getKey(), group.getValue().keySet()))
                .max(Comparator.comparingInt(group -> group.getValue().size()));
        if (largest.isEmpty()) {
            return List.of();
        }
        Map<String, Element> links = largest.get().getValue();
        List<Item> items = new ArrayList<>();
        links.forEach((url, anchor) -> {
            items.add(new Item(url, title(anchor), card(anchor, links)));
        });
        return items;
    }

    /**
     * Подтверждение списка текстом (§183; §186 — порог ниже: лучше лишние страницы, чем потерянные вакансии):
     * открываются первые {@value #VACANCY_SAMPLE} страницы вакансий; список подтверждён, если хотя бы одна из
     * открывшихся — с признаком текста вакансии ({@link #JOB_TEXT}) в основном тексте ({@link #mainText}). Ни одна не
     * открылась, ни в одной нет признака или список пуст — не подтверждён.
     *
     * @param urls адреса вакансий списка в порядке страницы
     * @param open открывает страницу; пусто — не открылась
     * @return {@code true} — ссылки списка ведут на вакансии
     */
    public static boolean confirmed(List<String> urls, Function<URI, Optional<Document>> open) {
        return urls.stream().limit(VACANCY_SAMPLE).map(CareerPageList::uri).filter(Objects::nonNull)
                .map(open).flatMap(Optional::stream).anyMatch(page -> looksLikeVacancy(mainText(page)));
    }

    /**
     * Основной текст страницы (§186): {@code main}; нет — {@code body} без {@code header}, {@code nav},
     * {@code footer} — признаки вакансии в меню и подвале сайта (ссылка «Kariéra», «Pošlite životopis») не в счёт.
     */
    static String mainText(Document page) {
        Element main = page.selectFirst("main");
        if (main != null) {
            return main.text();
        }
        Element body = page.body().clone();
        body.select(String.join(",", LAYOUT_TAGS)).remove();
        return body.text();
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
            if (next && link != null && !link.equals(base) && sameSite(base.getHost(), link.getHost())
                    && !visited.contains(link.toString())) {
                return Optional.of(link);
            }
        }
        // Нумерация без «далее» (§255: Manuvia «1 2 … 16» — ?strana=2, Lugera — /page/2/): ссылка с номером следующей
        // страницы, номер — в её адресе.
        String following = String.valueOf(pageNumber(base) + 1);
        for (Element anchor : page.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            if (link != null && following.equals(anchor.text().strip()) && sameSite(base.getHost(), link.getHost())
                    && PAGE_NUMBER.matcher(link.toString()).results()
                            .anyMatch(number -> following.equals(number.group(1)))
                    && !visited.contains(link.toString())) {
                return Optional.of(link);
            }
        }
        return Optional.empty();
    }

    /**
     * @param address адрес страницы списка
     * @return номер страницы из адреса ({@code ?strana=3}, {@code ?page=3}, {@code /page/3/}); нет — 1
     */
    static int pageNumber(URI address) {
        return PAGE_NUMBER.matcher(address.toString()).results().map(found -> Integer.parseInt(found.group(1)))
                .findFirst().orElse(1);
    }

    /**
     * @param parent родительский путь группы ссылок
     * @return {@code true} — последняя часть пути — раздел не вакансий ({@link #NOT_JOBS_SEGMENT})
     */
    private static boolean notJobs(String parent) {
        String trimmed = parent.replaceAll("/+$", "");
        return NOT_JOBS_SEGMENT.matcher(trimmed.substring(trimmed.lastIndexOf('/') + 1)).matches();
    }

    /**
     * Ссылка «следующая страница», которую листает скрипт (§252: Tatra banka — «Ďalšia strana» с {@code href="#"}): по
     * ней не перейти, список прочитан не весь.
     *
     * @param page страница списка
     * @return {@code true} — есть активная ссылка «далее» на {@code #}, {@code javascript:} или на саму страницу
     */
    public static boolean scriptNext(Document page) {
        return page.select("a").stream()
                .filter(anchor -> NEXT_WORD.matcher(plain(anchor.text())).matches())
                .filter(anchor -> !disabled(anchor) && !disabled(anchor.parent()))
                .anyMatch(anchor -> {
                    String href = anchor.attr("href").strip().toLowerCase(Locale.ROOT);
                    URI target = uri(anchor.absUrl("href"));
                    URI self = uri(page.location());
                    // Без href — только кнопка скрипта (onclick или блок листания), не вкладка и не аккордеон (аудит §257).
                    return !anchor.hasAttr("href") && (anchor.hasAttr("onclick") || inPaging(anchor))
                            || anchor.hasAttr("href") && (href.isEmpty() || href.startsWith("#")
                                    || href.startsWith("javascript:") || target != null && target.equals(self));
                });
    }

    /**
     * @return {@code true} — ссылка в блоке листания: у родителя или деда класс с «pag» (pagination, pager, paging)
     */
    private static boolean inPaging(Element anchor) {
        return anchor.parents().stream().limit(2)
                .anyMatch(parent -> parent.className().toLowerCase(Locale.ROOT).contains("pag"));
    }

    /**
     * @return {@code true} — элемент выключен: атрибут {@code disabled}, {@code aria-disabled="true"} или класс с
     *         «disabled» (Bootstrap ставит его на {@code <li>} вокруг ссылки последней страницы, аудит §257)
     */
    private static boolean disabled(Element element) {
        return element != null && (element.hasAttr("disabled") || "true".equals(element.attr("aria-disabled"))
                || element.className().toLowerCase(Locale.ROOT).contains("disabled"));
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
     * @param parent родительский путь группы ссылок
     * @param urls   адреса ссылок группы
     * @return кадровое слово в родительском пути или в хосте хотя бы одной ссылки группы
     */
    private static boolean underCareer(String parent, Set<String> urls) {
        return CAREER_PATH.matcher(parent).find() || urls.stream().map(CareerPageList::uri).filter(Objects::nonNull)
                .anyMatch(link -> CAREER_PATH.matcher(link.getHost()).find());
    }

    /**
     * Название вакансии (§252, эталон §247): заголовок внутри ссылки ({@link #TITLE_SELECTORS}) — ссылка охватывает всю
     * карточку (Penta: «… Nemocnice Penta Hospitals …», BENU: «… 1600 EUR / Mesiac»); текст ссылки — кнопка
     * («viac info», «Viac informácií») — заголовок карточки вокруг ссылки (XENEX, Manuvia); иначе текст ссылки. Номер
     * карточки впереди отрезается.
     *
     * @param anchor ссылка
     * @return название; пусто — нет
     */
    static String title(Element anchor) {
        String title = heading(anchor).orElse(anchor.text());
        if (NOT_TITLE.matcher(plain(title)).matches() || title.isBlank()) {
            String href = anchor.attr("href");
            String text = anchor.text();
            Element around = anchor;
            for (int level = 0; level < CARD_LEVELS && around.parent() != null; level++) {
                around = around.parent();
                // Родитель с такой же кнопкой другой вакансии — уже не карточка, а список.
                if (around.text().length() > MAX_TITLE_CARD || around.select("a[href]").stream()
                        .anyMatch(other -> !other.attr("href").equals(href) && other.text().equals(text))) {
                    break;
                }
                Optional<String> found = heading(around);
                if (found.isPresent()) {
                    title = found.get();
                    break;
                }
            }
        }
        return LEADING_COUNTER.matcher(title.replaceAll("\\s+", " ").strip()).replaceFirst("");
    }

    /**
     * @param element ссылка или карточка
     * @return текст первого непустого элемента названия ({@link #TITLE_SELECTORS} по порядку) внутри; пусто — нет
     */
    private static Optional<String> heading(Element element) {
        for (String selector : TITLE_SELECTORS) {
            for (Element found : element.select(selector)) {
                if (!found.text().isBlank() && !NOT_TITLE.matcher(plain(found.text())).matches()) {
                    return Optional.of(found.text());
                }
            }
        }
        return Optional.empty();
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
                && !inLayout(anchor);
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
        // Заголовок карточки — название, а не место (аудит §257: карточка «h3 + кнопка Detail»).
        copy.select(TITLE_SELECTORS.get(0)).remove();
        return copy.text().replaceAll("\\s+", " ").strip();
    }

    /**
     * Ссылка в меню, шапке или подвале сайта (§255): {@code nav} и {@code footer} — всегда (подвал записи WordPress в
     * {@code article} — метки и рубрики, аудит §257); {@code header} — только сайта: внутри {@code article} или
     * {@code main} это шапка карточки вакансии (AGEL: название — в {@code <article><header>}).
     */
    private static boolean inLayout(Element anchor) {
        for (Element parent : anchor.parents()) {
            String tag = parent.tagName();
            if ("nav".equals(tag) || "footer".equals(tag) || "header".equals(tag)
                    && parent.parents().stream().noneMatch(outer -> CARD_CONTAINERS.contains(outer.tagName()))) {
                return true;
            }
        }
        return false;
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
