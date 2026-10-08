package com.roleorienta.worker.career;

import com.roleorienta.worker.adapter.greenhouse.GreenhouseAdapter;
import com.roleorienta.worker.adapter.nalgoo.NalgooAdapter;
import com.roleorienta.worker.adapter.personio.PersonioAdapter;
import com.roleorienta.worker.adapter.phenom.PhenomAdapter;
import com.roleorienta.worker.adapter.smartrecruiters.SmartRecruitersAdapter;
import com.roleorienta.worker.adapter.successfactors.SuccessFactorsAdapter;
import com.roleorienta.worker.adapter.taleo.TaleoAdapter;
import com.roleorienta.worker.adapter.workday.WorkdayAdapter;
import com.roleorienta.worker.site.SiteBrand;
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
 *   <li>SAP SuccessFactors Career Site Builder — сама страница с ресурсами {@code rmkcdn.successfactors.com} →
 *       хост страницы (§57).</li>
 *   <li>Nalgoo — {@code <организация>.nalgoo-jobs.com}, {@code ats.nalgoo.com/<язык>/gate/<организация>/…} или сама
 *       страница со своим доменом и данными {@code ats.nalgoo.com/api} + {@code "organization"} → организация (§58).</li>
 *   <li>Phenom — сама страница с ресурсами {@code cdn.phenompeople.com} и данными сайта {@code "baseUrl"} → хост и путь
 *       языка из {@code baseUrl} ({@code careers.dhl.com/eu/sk}; §59).</li>
 *   <li>Oracle Taleo — адрес страницы поиска или вакансии {@code <компания>.taleo.net/careersection/<раздел>/
 *       jobsearch.ftl} ({@code jobdetail.ftl}, {@code moresearch.ftl}) в тексте страницы (бывает только в данных
 *       скрипта, с экранированными {@code \/}) → {@code <компания>.taleo.net/<раздел>} (§60; вход {@code …/iam/…},
 *       служебные {@code rest}, {@code theme} — не разделы, аудит §65).</li>
 *   <li>Кадровая страница — как в скрипте замера топ-500 ({@code survey/employer-survey.py}, §76): ссылка, в тексте
 *       или адресе которой кадровое слово ({@link #CAREER_WORDS}). Принимается ссылка того же регистрируемого домена
 *       ({@code kariera.firma.sk}, {@code www.firma.sk} с {@code sk.firma.sk}); ссылка на кадровый хост другого домена с
 *       тем же именем домена ({@code jobs.kaufland.com} с {@code kaufland.sk}, §57) — и без кадрового слова; ссылка с
 *       кадровым словом на систему найма ({@code acme.teamtailor.com}) или на хост с брендом сайта
 *       ({@code acme-group.com}). Площадки вакансий ({@code jobs.cz}, {@code teamio} …) не принимаются. Ссылки своего
 *       домена идут первыми; кадровый хост с другим именем ({@code jobs.sap.com} с {@code firma.sk}) не принимается —
 *       иначе доски чужой кадровой страницы записались бы как доски компании (аудит §66).</li>
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
    private static final String SUCCESS_FACTORS_CDN = "rmkcdn.successfactors.com";
    private static final Pattern NALGOO = Pattern.compile(
            "(?:([a-z0-9-]+)\\.nalgoo-jobs\\.com|ats\\.nalgoo\\.com/[a-z]{2}/gate/([a-z0-9-]+))(?=$|[/?#])",
            Pattern.CASE_INSENSITIVE);
    private static final String NALGOO_API = "ats.nalgoo.com/api";
    private static final Pattern NALGOO_ORGANIZATION = Pattern.compile(
            "\\\\?\"organization\\\\?\"\\s*:\\s*\\\\?\"([a-z0-9-]+)", Pattern.CASE_INSENSITIVE);
    private static final String PHENOM_CDN = "cdn.phenompeople.com";
    private static final Pattern PHENOM_BASE_URL = Pattern.compile("\"baseUrl\"\\s*:\\s*\"https?://([^\"?#]+?)/?\"");
    private static final Pattern TALEO = Pattern.compile(
            "(?<![a-z0-9-])([a-z0-9-]+\\.taleo\\.net)/careersection/([a-z0-9_]+)/(?:jobsearch|jobdetail|moresearch)\\.ftl",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> CAREER_HOST_WORDS = Set.of("jobs", "careers", "career", "kariera", "karriere");
    private static final int CAREER_HOST_LABELS = 3;
    private static final Set<String> GREENHOUSE_NOT_BOARDS = Set.of("embed", "robots", "favicon");
    /**
     * Кадровые слова — словарь скрипта замера ({@code CAREER_WORDS} в {@code survey/employer-survey.py}). «Práca» — только
     * в сочетаниях («práca u nás», «práca v …»): голое слово находится внутри «spolupráca» (страницы о сотрудничестве,
     * §75).
     */
    private static final Pattern CAREER_WORDS = Pattern.compile(
            "kari[eé]r|career|\\bjobs?\\b|pr[aá]ca u n[aá]s|pr[aá]ca vo? |pre uch[aá]dza[čc]"
                    + "|vo[ľl]n[ée] (?:poz[ií]cie|miesta|pracovn)|pracovn[ée] (?:ponuky|poz[ií]cie|miesta)|ponuky pr[aá]ce"
                    + "|pridaj sa|po[ďd]te k n[aá]m|join us|work with us|zamestnanie|n[aá]bor",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Сколько первых символов текста страницы проверяется на кадровые слова (как в скрипте). */
    private static final int CAREER_TEXT_LIMIT = 300_000;
    /** Площадки вакансий и продажи доменов — не кадровая страница компании (скрипт: {@code FOOTER_HOSTS}). */
    private static final Pattern JOB_PORTAL_HOSTS = Pattern.compile(
            "almacareer|jobs\\.cz|prace\\.cz|atmoskop|platy\\.(?:sk|cz)|teamio|cvonline|hugedomains|sedo|afternic",
            Pattern.CASE_INSENSITIVE);
    /** Хосты систем найма (скрипт: {@code ATS_HOSTS}). */
    private static final Pattern RECRUITING_HOSTS = Pattern.compile(
            "myworkdayjobs|greenhouse|personio|smartrecruiters|successfactors|nalgoo|nelisa|topjobs|teamtailor|recruitee"
                    + "|lever\\.co|workable|breezy|softgarden|taleo|oraclecloud|icims|eightfold|avature|phenom|radancy"
                    + "|talentbrew|traffit|jobangels",
            Pattern.CASE_INSENSITIVE);
    /** Бренд сайта короче — в чужом хосте не ищется (скрипт: {@code len(brand) >= 3}). */
    private static final int MIN_BRAND = 3;

    private CareerLinks() {
    }

    /**
     * @param page страница
     * @return доски систем найма, на которые она ссылается
     */
    static Set<Board> boards(Document page) {
        Set<Board> boards = new LinkedHashSet<>();
        successFactorsSite(page).ifPresent(boards::add);
        nalgooSite(page).ifPresent(boards::add);
        phenomSite(page).ifPresent(boards::add);
        boards.addAll(taleoBoards(page));
        hrefs(page).forEach(href -> board(href).ifPresent(boards::add));
        return boards;
    }

    /**
     * Страница сама — кадровый сайт Nalgoo на своём домене ({@code kariera.foxconn.sk}): в данных страницы — адрес API
     * {@code ats.nalgoo.com/api} и имя организации ({@code "organization":"foxconn"}, кавычки бывают экранированы).
     *
     * @param page страница
     * @return доска — организация; пусто — не Nalgoo
     */
    static Optional<Board> nalgooSite(Document page) {
        String html = page.outerHtml();
        if (!html.contains(NALGOO_API)) {
            return Optional.empty();
        }
        Matcher organization = NALGOO_ORGANIZATION.matcher(html);
        return organization.find() ? Optional.of(new Board(NalgooAdapter.PROVIDER, lower(organization.group(1))))
                : Optional.empty();
    }

    /**
     * Кадровые разделы Taleo, названные на странице: ссылкой или адресом в данных скрипта ({@code https:\/\/molgroup.
     * taleo.net\/careersection\/external\/…} у Slovnaft).
     *
     * @param page страница
     * @return доски {@code <компания>.taleo.net/<раздел>}
     */
    static Set<Board> taleoBoards(Document page) {
        Set<Board> boards = new LinkedHashSet<>();
        Matcher taleo = TALEO.matcher(page.outerHtml().replace("\\/", "/"));
        while (taleo.find()) {
            boards.add(new Board(TaleoAdapter.PROVIDER, lower(taleo.group(1)) + "/" + lower(taleo.group(2))));
        }
        return boards;
    }

    /**
     * Страница сама — кадровый сайт Phenom: ресурсы с {@code cdn.phenompeople.com} и в данных сайта ({@code var phApp})
     * адрес сайта с путём языка — {@code "baseUrl":"https://careers.dhl.com/eu/sk/"}.
     *
     * @param page страница
     * @return доска — хост и путь языка из {@code baseUrl}; пусто — не Phenom
     */
    static Optional<Board> phenomSite(Document page) {
        String html = page.outerHtml();
        if (!html.contains(PHENOM_CDN)) {
            return Optional.empty();
        }
        Matcher base = PHENOM_BASE_URL.matcher(html);
        return base.find() && PhenomAdapter.BOARD.matcher(lower(base.group(1))).matches()
                ? Optional.of(new Board(PhenomAdapter.PROVIDER, lower(base.group(1)))) : Optional.empty();
    }

    /**
     * Страница сама — кадровый сайт SAP SuccessFactors Career Site Builder: ресурсы с {@code rmkcdn.successfactors.com}
     * (адрес сайта свой у каждого работодателя — {@code jobs.zf.com}, — поэтому узнаётся по содержимому).
     *
     * @param page страница с адресом ({@code baseUri})
     * @return доска — хост страницы; пусто — не SuccessFactors
     */
    static Optional<Board> successFactorsSite(Document page) {
        URI location = uri(page.location());
        if (location == null || location.getHost() == null || !page.outerHtml().contains(SUCCESS_FACTORS_CDN)) {
            return Optional.empty();
        }
        return Optional.of(new Board(SuccessFactorsAdapter.PROVIDER, lower(location.getHost())));
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
        Matcher nalgoo = NALGOO.matcher(link);
        if (nalgoo.lookingAt()) {
            String organization = lower(nalgoo.group(1) != null ? nalgoo.group(1) : nalgoo.group(2));
            return "www".equals(organization) ? Optional.empty()
                    : Optional.of(new Board(NalgooAdapter.PROVIDER, organization));
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
     * @param host хост сайта с портом, если он указан в адресе ({@code URI.getAuthority()})
     * @return адрес кадровой страницы: того же сайта или его поддомена, иначе кадрового хоста другого домена с тем же
     *         именем домена; пусто — ссылки нет
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
     * Проверка кадровой страницы, как в скрипте замера: кадровое слово в видимом тексте страницы (первые
     * {@value #CAREER_TEXT_LIMIT} символов). Страница по ссылке «Kariéra» или по пробному адресу без кадровых слов —
     * не кадровая (заглушка, страница о сотрудничестве).
     *
     * @param page открытая страница
     * @return {@code true} — в тексте страницы есть кадровое слово
     */
    static boolean isCareerPage(Document page) {
        String text = page.text();
        return CAREER_WORDS.matcher(text.substring(0, Math.min(text.length(), CAREER_TEXT_LIMIT))).find();
    }

    /**
     * Кадровые ссылки страницы (правила — в описании класса): сначала ссылки своего регистрируемого домена, затем
     * ссылки других доменов; адрес — без якоря ({@code /#career} → {@code /}: якорь серверу не передаётся).
     */
    private static List<String> careerLinks(Document page, String host) {
        String siteHost = host.replaceFirst(":\\d+$", "");
        String domain = SiteBrand.registrable(withoutWww(siteHost));
        String brand = domain.split("\\.")[0];
        List<String> own = new ArrayList<>();
        List<String> otherDomain = new ArrayList<>();
        for (Element anchor : page.select("a[href]")) {
            URI link = uri(anchor.absUrl("href"));
            if (link == null || link.getHost() == null) {
                continue;
            }
            String linkHost = lower(link.getHost());
            String address = link.toString().replaceFirst("#.*$", "");
            boolean careerWords = CAREER_WORDS.matcher(anchor.text() + " " + link).find();
            if (sameOrSubSite(host, link.getAuthority()) || SiteBrand.registrable(linkHost).equals(domain)) {
                if ((careerHost(linkHost) || careerWords) && !own.contains(address)) {
                    own.add(address);
                }
            } else if (!JOB_PORTAL_HOSTS.matcher(linkHost).find()
                    && (careerHost(linkHost) && sameDomainName(host, linkHost)
                        || careerWords && (RECRUITING_HOSTS.matcher(linkHost).find()
                            || brand.length() >= MIN_BRAND && linkHost.contains(brand)))
                    && !otherDomain.contains(address)) {
                otherDomain.add(address);
            }
        }
        own.addAll(otherDomain);
        return own;
    }

    /**
     * Одно имя домена без учёта зоны: {@code kaufland} у {@code www.kaufland.sk} и {@code jobs.kaufland.com}.
     */
    private static boolean sameDomainName(String host, String other) {
        String[] hostLabels = withoutWww(host).split("\\.");
        String[] otherLabels = lower(other).split("\\.");
        return hostLabels.length >= 2 && otherLabels.length >= 2
                && hostLabels[hostLabels.length - 2].equals(otherLabels[otherLabels.length - 2]);
    }

    /**
     * Кадровый хост другого домена: первое слово — {@code jobs}, {@code careers}, {@code career}, {@code kariera},
     * {@code karriere}, и в хосте не меньше трёх частей ({@code jobs.kaufland.com}, но не площадка {@code jobs.cz}).
     */
    private static boolean careerHost(String host) {
        if (host == null) {
            return false;
        }
        String[] labels = lower(host).split("\\.");
        return labels.length >= CAREER_HOST_LABELS && CAREER_HOST_WORDS.contains(labels[0]);
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
