package com.roleorienta.worker.career;

import com.roleorienta.worker.adapter.greenhouse.GreenhouseAdapter;
import com.roleorienta.worker.adapter.personio.PersonioAdapter;
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
 * <ul>
 *   <li>Workday — {@code <тенант>.wd<N>.myworkdayjobs.com/[<язык>/]<сайт>} → доска
 *       {@code <тенант>.wd<N>.myworkdayjobs.com/<сайт>}.</li>
 *   <li>Greenhouse — {@code boards.greenhouse.io/<доска>}, {@code job-boards.greenhouse.io/<доска>},
 *       встраивание {@code …/embed/job_board?for=<доска>} → доска.</li>
 *   <li>Personio — {@code <компания>.jobs.personio.de|com} → хост витрины.</li>
 *   <li>Кадровая страница — ссылка того же сайта (хост без учёта {@code www.}), в адресе или тексте которой «kariéra», «práca»,
 *       «jobs», «career», «voľné pozície» и т. п.</li>
 * </ul>
 */
final class CareerLinks {

    private static final Pattern WORKDAY = Pattern.compile(
            "([a-z0-9-]+\\.wd\\d+\\.myworkdayjobs\\.com)/(?:[a-z]{2}-[A-Z]{2}/)?([A-Za-z0-9_-]+)");
    private static final Pattern GREENHOUSE = Pattern.compile(
            "(?:job-)?boards\\.greenhouse\\.io/(?:embed/job_board(?:/js)?\\?for=)?([A-Za-z0-9_-]+)");
    private static final Pattern PERSONIO = Pattern.compile("([a-z0-9-]+\\.jobs\\.personio\\.(?:de|com))");
    private static final Set<String> GREENHOUSE_NOT_BOARDS = Set.of("embed", "robots", "favicon");
    private static final List<String> CAREER_WORDS = List.of("kariera", "kariéra", "career", "jobs", "job-",
            "praca", "práca", "pracovne-ponuky", "pracovné ponuky", "volne-pozicie", "voľné pozície",
            "volne-miesta", "voľné miesta", "pridaj-sa", "pridajte sa", "join-us", "hiring");

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
        String link = url.replaceFirst("^[a-z]+://", "");
        Matcher workday = WORKDAY.matcher(link);
        if (workday.lookingAt()) {
            return Optional.of(new Board(WorkdayAdapter.PROVIDER, workday.group(1) + "/" + workday.group(2)));
        }
        Matcher greenhouse = GREENHOUSE.matcher(link);
        if (greenhouse.lookingAt() && !GREENHOUSE_NOT_BOARDS.contains(greenhouse.group(1))) {
            return Optional.of(new Board(GreenhouseAdapter.PROVIDER, greenhouse.group(1)));
        }
        Matcher personio = PERSONIO.matcher(link);
        return personio.lookingAt() ? Optional.of(new Board(PersonioAdapter.PROVIDER, personio.group(1)))
                : Optional.empty();
    }

    /**
     * @param page страница сайта
     * @param host хост сайта
     * @return адрес кадровой страницы того же хоста; пусто — ссылки нет
     */
    static Optional<String> careerPage(Document page, String host) {
        for (Element anchor : page.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            if (link == null || !sameSite(host, link.getAuthority())) {
                continue;
            }
            String words = ((link.getPath() == null ? "" : link.getPath()) + " " + anchor.text())
                    .toLowerCase(Locale.ROOT);
            if (CAREER_WORDS.stream().anyMatch(words::contains)) {
                return Optional.of(link.toString());
            }
        }
        return Optional.empty();
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
