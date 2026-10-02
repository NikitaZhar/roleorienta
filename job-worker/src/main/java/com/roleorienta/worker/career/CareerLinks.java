package com.roleorienta.worker.career;

import com.roleorienta.worker.adapter.greenhouse.GreenhouseAdapter;
import com.roleorienta.worker.adapter.personio.PersonioAdapter;
import com.roleorienta.worker.adapter.smartrecruiters.SmartRecruitersAdapter;
import com.roleorienta.worker.adapter.workday.WorkdayAdapter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Разбор страницы сайта компании: ссылки на доски поддерживаемых систем найма, ссылка на
 * кадровую страницу, разметка {@code JobPosting}.
 *
 * <p>Ключ доски — в нижнем регистре: все четыре провайдера регистр в адресе доски не различают
 * (Workday — сайт тенанта, Greenhouse — имя доски, Personio и SmartRecruiters — хост и имя компании),
 * поэтому {@code …/AccentureCareers} и {@code …/accenturecareers} — одна доска, а не две.</p>
 *
 * <ul>
 *   <li>Workday — {@code <тенант>.wd<N>.myworkdayjobs.com/[<язык>/]<сайт>} → доска
 *       {@code <тенант>.wd<N>.myworkdayjobs.com/<сайт>}. Язык — {@code es} или {@code en-US}; сайт —
 *       целый сегмент пути, не файл ({@code robots.txt}), не код языка и не служебный путь
 *       ({@code wday}).</li>
 *   <li>Greenhouse — {@code boards.greenhouse.io/<доска>}, {@code job-boards.greenhouse.io/<доска>},
 *       встраивание {@code …/embed/job_board?for=<доска>} → доска.</li>
 *   <li>Personio — {@code <компания>.jobs.personio.de|com} → хост витрины.</li>
 *   <li>SmartRecruiters — {@code careers.smartrecruiters.com/<компания>},
 *       {@code jobs.smartrecruiters.com/<компания>/…} → компания.</li>
 *   <li>Кадровая страница — ссылка того же сайта (хост без учёта {@code www.}) или его поддомена
 *       ({@code kariera.firma.sk}, {@code jobs.firma.sk}), в адресе или тексте которой «kariéra», «práca»,
 *       «jobs», «career», «voľné pozície» и т. п.</li>
 * </ul>
 */
final class CareerLinks {

    private static final Pattern WORKDAY = Pattern.compile(
            "([a-z0-9-]+\\.wd\\d+\\.myworkdayjobs\\.com)/(?:[a-z]{2}(?:-[A-Z]{2})?/)?([A-Za-z0-9_-]+)(?=$|[/?#])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_WORKDAY_SITE = Pattern.compile("robots|wday|[a-z]{2}(?:-[a-z]{2})?");
    private static final Pattern GREENHOUSE = Pattern.compile(
            "(?:job-)?boards\\.greenhouse\\.io/(?:embed/job_board(?:/js)?\\?for=)?([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSONIO = Pattern.compile("([a-z0-9-]+\\.jobs\\.personio\\.(?:de|com))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SMARTRECRUITERS = Pattern.compile(
            "(?:careers|jobs)\\.smartrecruiters\\.com/([A-Za-z0-9_-]+)(?=$|[/?#])", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SMARTRECRUITERS_NOT_BOARDS = Set.of("robots", "sitemap", "api", "static", "oneclick-ui");
    private static final Set<String> GREENHOUSE_NOT_BOARDS = Set.of("embed", "robots", "favicon");
    private static final List<String> CAREER_WORDS = List.of("kariera", "kariéra", "career", "jobs", "job-",
            "praca", "práca", "pracovne-ponuky", "pracovné ponuky", "volne-pozicie", "voľné pozície",
            "volne-miesta", "voľné miesta", "pridaj-sa", "pridajte sa", "join-us", "hiring",
            "uchadzac", "uchádzač", "poďte k nám", "podte-k-nam", "join us", "work with us", "work-with-us");

    private CareerLinks() {
    }

    /**
     * @param page страница
     * @return доски систем найма, на которые она ссылается
     */
    static Set<Board> boards(Document page) {
        Set<Board> boards = new LinkedHashSet<>();
        hrefs(page).forEach(href -> board(href).ifPresent(boards::add));
        return boards;
    }

    /**
     * @param url адрес
     * @return доска системы найма, на которую указывает адрес; пусто — не доска
     */
    static Optional<Board> board(String url) {
        String link = url.replaceFirst("^[A-Za-z]+://", "");
        Matcher workday = WORKDAY.matcher(link);
        if (workday.lookingAt()) {
            String site = lower(workday.group(2));
            return NOT_WORKDAY_SITE.matcher(site).matches() ? Optional.empty()
                    : Optional.of(new Board(WorkdayAdapter.PROVIDER, lower(workday.group(1)) + "/" + site));
        }
        Matcher greenhouse = GREENHOUSE.matcher(link);
        if (greenhouse.lookingAt() && !GREENHOUSE_NOT_BOARDS.contains(lower(greenhouse.group(1)))) {
            return Optional.of(new Board(GreenhouseAdapter.PROVIDER, lower(greenhouse.group(1))));
        }
        Matcher smartRecruiters = SMARTRECRUITERS.matcher(link);
        if (smartRecruiters.lookingAt()) {
            String company = lower(smartRecruiters.group(1));
            return SMARTRECRUITERS_NOT_BOARDS.contains(company) ? Optional.empty()
                    : Optional.of(new Board(SmartRecruitersAdapter.PROVIDER, company));
        }
        Matcher personio = PERSONIO.matcher(link);
        return personio.lookingAt() ? Optional.of(new Board(PersonioAdapter.PROVIDER, lower(personio.group(1))))
                : Optional.empty();
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * @param board доска, записанная раньше
     * @return доска по правилам {@link #board}; {@code false} — мусор прежних правил
     *         ({@code …/robots}, {@code …/es}), проверять нечего
     */
    static boolean isBoard(Board board) {
        return !WorkdayAdapter.PROVIDER.equals(board.provider())
                || board("https://" + board.board()).filter(board::equals).isPresent();
    }

    /**
     * @param page страница сайта
     * @param host хост сайта
     * @return адрес кадровой страницы того же сайта или его поддомена; пусто — ссылки нет
     */
    static Optional<String> careerPage(Document page, String host) {
        return careerLinks(page, host).stream().findFirst();
    }

    /**
     * Шаг вглубь: на кадровой странице без досок — ссылка на другую кадровую страницу
     * ({@code /kariera} → {@code /kariera/volne-pozicie}).
     *
     * @param page       кадровая страница
     * @param careerPage её адрес
     * @return адрес другой кадровой страницы того же сайта; пусто — нет
     */
    static Optional<String> deeperCareerPage(Document page, URI careerPage) {
        String current = normalized(careerPage.toString());
        return careerLinks(page, careerPage.getAuthority()).stream()
                .filter(link -> !normalized(link).equals(current)).findFirst();
    }

    /**
     * @param page страница, открытая по стандартному адресу ({@code /kariera})
     * @return {@code true} — в заголовке страницы есть кадровое слово: это кадровая страница, а не
     *         заглушка сайта, отвечающая на любой адрес
     */
    static boolean isCareerPage(Document page) {
        String heading = (page.title() + " " + page.select("h1").text()).toLowerCase(Locale.ROOT);
        return CAREER_WORDS.stream().anyMatch(heading::contains);
    }

    private static List<String> careerLinks(Document page, String host) {
        List<String> links = new ArrayList<>();
        for (Element anchor : page.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            if (link == null || !sameOrSubSite(host, link.getAuthority())) {
                continue;
            }
            String words = ((link.getPath() == null ? "" : link.getPath()) + " " + anchor.text())
                    .toLowerCase(Locale.ROOT);
            if (CAREER_WORDS.stream().anyMatch(words::contains) && !links.contains(link.toString())) {
                links.add(link.toString());
            }
        }
        return links;
    }

    private static String normalized(String link) {
        return link.replaceFirst("[?#].*$", "").replaceFirst("/+$", "");
    }

    /**
     * @param page       кадровая страница
     * @param careerPage её адрес
     * @param limit      сколько ссылок вернуть
     * @return ссылки того же хоста с путём глубже кадровой страницы — вероятные страницы вакансий
     */
    static List<String> vacancyLinks(Document page, URI careerPage, int limit) {
        String prefix = careerPage.getPath() == null ? "/" : careerPage.getPath().replaceAll("/+$", "") + "/";
        List<String> links = new ArrayList<>();
        for (Element anchor : page.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            if (link != null && sameSite(careerPage.getAuthority(), link.getAuthority())
                    && link.getPath() != null && link.getPath().startsWith(prefix)
                    && link.getPath().length() > prefix.length() && !links.contains(link.toString())) {
                links.add(link.toString());
                if (links.size() == limit) {
                    break;
                }
            }
        }
        return links;
    }

    /**
     * @param page страница
     * @return {@code true} — в блоках JSON-LD страницы есть тип {@code JobPosting}
     */
    static boolean hasJobPosting(Document page) {
        return page.select("script[type=application/ld+json]").stream()
                .anyMatch(script -> script.data().contains("\"JobPosting\""));
    }

    private static List<String> hrefs(Document page) {
        List<String> hrefs = new ArrayList<>();
        page.select("a[href], iframe[src], script[src]").forEach(element -> {
            String link = element.hasAttr("href") ? element.absUrl("href") : element.absUrl("src");
            if (!link.isEmpty()) {
                hrefs.add(link);
            }
        });
        return hrefs;
    }

    /**
     * Один сайт: хосты совпадают без учёта регистра и префикса {@code www.}.
     */
    private static boolean sameSite(String host, String other) {
        return withoutWww(host).equals(withoutWww(other));
    }

    /**
     * Тот же сайт или его поддомен: {@code kariera.firma.sk} для {@code www.firma.sk}.
     */
    private static boolean sameOrSubSite(String host, String other) {
        return sameSite(host, other) || withoutWww(other).endsWith("." + withoutWww(host));
    }

    private static String withoutWww(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.startsWith("www.") ? lower.substring(4) : lower;
    }

    private static URI uri(String link) {
        try {
            URI uri = new URI(link);
            return uri.getAuthority() == null ? null : uri;
        } catch (URISyntaxException malformed) {
            return null;
        }
    }
}
