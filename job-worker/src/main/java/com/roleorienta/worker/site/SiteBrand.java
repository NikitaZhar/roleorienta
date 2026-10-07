package com.roleorienta.worker.site;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;

/**
 * Бренд из названия компании на сайте (алгоритм поиска сайта версии 3, шаг 4; технический документ §5.1).
 * Бренд — первое слово названия без правовой формы и первые два слова (слитно и через дефис); общие слова
 * (obec, group, slovakia …) брендом не считаются. Бренд подтверждает сайт, если:
 *
 * <ul>
 *   <li>страница не заглушка продажи домена и в её видимом тексте не меньше {@link #MIN_WORDS} слов;</li>
 *   <li>сайт в зоне {@code .sk} либо есть признак Словакии (slovensk…, slovakia в тексте или сегмент пути
 *       {@code /sk/});</li>
 *   <li>бренд ({@link #keys}: первое слово, первые два слова слитно) совпадает с первым словом домена (или, от
 *       трёх букв, содержится в нём) и есть отдельным словом в видимом тексте (текст сравнивается без
 *       диакритики); бренд до трёх букв — ещё и в заголовке {@code title}.</li>
 * </ul>
 *
 * <p>Домен, зона и путь берутся из конечного адреса страницы после переадресаций. Сайт группы
 * ({@link #groupSite}) — кандидат, не находка. Правила — как в скрипте замера {@code survey/site-search.py}, на
 * котором измерен топ-500 (стенограмма §73; правило двух слов §51 снято).</p>
 */
final class SiteBrand {

    /** Меньше слов в видимом тексте — заглушка или пустая страница. */
    static final int MIN_WORDS = 30;

    private static final int SHORT_BRAND_MAX = 3;
    private static final int GROUP_BRAND_MIN = 4;
    private static final Pattern BRAND = Pattern.compile("[a-z0-9-]+");
    private static final Pattern PAIR = Pattern.compile("\\s*([A-Za-z]+)\\s*&\\s*([A-Za-z]+)");
    private static final int NAME_DOMAIN_MIN = 4;
    private static final String WWW = "www.";
    private static final int RAW_SCAN_LIMIT = 300_000;
    private static final Pattern LEGAL_FORMS = Pattern.compile(
            "\\b(s\\.?\\s?r\\.?\\s?o\\.?|a\\.?\\s?s\\.?|spol\\.?\\s*s\\s*r\\.?\\s?o\\.?|v\\.?\\s?o\\.?\\s?s\\.?|"
            + "k\\.?\\s?s\\.?|se|organizacna zlozka.*|pobocka.*|slovenska republika|slovakia|slovensko|sr|"
            + "spolocnost s rucenim obmedzenym|akciova spolocnost)\\b");
    private static final Pattern PARKED = Pattern.compile("(?i)domain (is )?for sale|dom[eé]na je na predaj|"
            + "buy this domain|parked|hugedomains|sedo\\.com|afternic|dan\\.com|godaddy|domain_profile");
    private static final Pattern SLOVAK_SIGNAL = Pattern.compile("(?iu)slovensk|\\bslovakia\\b|\\bslovak republic\\b");
    private static final Set<String> SLOVAK_SEGMENTS = Set.of("sk", "sk-sk", "sk_sk", "slovakia");
    /** Второй уровень в двухбуквенной зоне: регистрируемый домен — три последние части ({@code firma.co.kr}). */
    private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "org", "net", "ac");
    private static final Set<String> SKIPPED_WORDS = Set.of("a", "the", "and");
    /** Общие слова названий: сайт с таким доменом подтверждается только IČO, не брендом. */
    private static final Set<String> GENERIC = Set.of(("obec mesto mestska skola zakladna stredna spojena gymnazium "
            + "materska sukromna senior seniori centrum technicka technicke euro eko agro stav stavby servis sluzby "
            + "domov nemocnica klub group trade invest energy auto transport logistics logistika consulting system "
            + "systems tech media plus slovak slovensko slovakia sk sr union global service services company "
            + "spolocnost druzstvo podnik farma market obchod real reality smart green best top super slovenske")
            .split(" "));

    private SiteBrand() {
    }

    /**
     * @param host        конечный хост сайта после переадресаций (по нему — первое слово домена и зона)
     * @param page        разобранная страница; её адрес (конечный) — для сегмента пути {@code /sk/}
     * @param html        исходный текст страницы — для признаков заглушки
     * @param companyName название компании из реестра
     * @return подтверждает ли бренд сайт
     */
    static boolean confirmed(String host, Document page, String html, String companyName) {
        String text = ascii(page.text());
        if (parked(html) || text.split("\\s+").length < MIN_WORDS) {
            return false;
        }
        String plainHost = host.toLowerCase(Locale.ROOT);
        if (!plainHost.endsWith(".sk") && !slovakSignal(page.location(), text)) {
            return false;
        }
        List<String> inText = new ArrayList<>();
        for (String brand : keys(companyName)) {
            if (matchesDomain(brand, plainHost) && word(brand).matcher(text).find()) {
                inText.add(brand);
            }
        }
        if (inText.isEmpty()) {
            return false;
        }
        if (inText.stream().allMatch(brand -> brand.length() <= SHORT_BRAND_MAX)) {
            return inText.stream().anyMatch(brand -> word(brand).matcher(ascii(page.title())).find());
        }
        return true;
    }

    /**
     * Международный сайт группы — кандидат (шаг 4): сайт не в {@code .sk}, бренд от {@link #GROUP_BRAND_MIN} букв —
     * первое слово домена, не заглушка и не меньше {@link #MIN_WORDS} слов.
     *
     * @param host        хост сайта
     * @param page        разобранная страница
     * @param html        исходный текст страницы
     * @param companyName название компании из реестра
     * @return похож ли сайт на сайт группы компании
     */
    static boolean groupSite(String host, Document page, String html, String companyName) {
        String plainHost = host.toLowerCase(Locale.ROOT);
        return !plainHost.endsWith(".sk") && keys(companyName).stream()
                .anyMatch(key -> key.length() >= GROUP_BRAND_MIN && key.equals(firstLabel(plainHost)))
                && !parked(html) && page.text().split("\\s+").length >= MIN_WORDS;
    }

    /**
     * Бренды названия для адресов: первое слово без правовой формы, первые два слова слитно и через дефис;
     * только буквы, цифры и дефис, не короче двух знаков (без дефиса).
     *
     * @param companyName название компании
     * @return бренды по порядку, без повторов
     */
    static List<String> brands(String companyName) {
        List<String> clean = new ArrayList<>();
        for (String word : asciiWords(companyName)) {
            String stripped = word.replaceAll("^\\.+|\\.+$", "");
            if (!stripped.isEmpty() && !word.endsWith(".sk")) {
                clean.add(stripped);
            }
        }
        Set<String> brands = new LinkedHashSet<>();
        if (!clean.isEmpty()) {
            brands.add(clean.get(0));
            if (clean.size() > 1) {
                brands.add(clean.get(0) + clean.get(1));
                brands.add(clean.get(0) + "-" + clean.get(1));
            }
        }
        brands.removeIf(brand -> !BRAND.matcher(brand).matches() || brand.replace("-", "").length() < 2);
        return new ArrayList<>(brands);
    }

    /**
     * Бренды, которыми подтверждается сайт: {@link #brands} без общих слов (obec, group …), без дефиса.
     *
     * @param companyName название компании
     * @return бренды по порядку, без повторов
     */
    static Set<String> keys(String companyName) {
        Set<String> keys = new LinkedHashSet<>();
        for (String brand : brands(companyName)) {
            if (!GENERIC.contains(brand)) {
                keys.add(brand.replace("-", ""));
            }
        }
        return keys;
    }

    /**
     * Адреса по названию (шаги 3–4 алгоритма версии 3): домен {@code .sk} из самого названия; для каждого бренда
     * {@code www.бренд.sk}, {@code www.бренд-slovakia.sk}, {@code www.брендslovakia.sk}, {@code www.бренд(-)slovensko.sk},
     * {@code www.бренд(-)jobs.sk}, {@code www.бренд-kariera.sk}, {@code kariera.бренд.sk}, {@code www.бренд.com/sk/},
     * {@code www.бренд.com/slovakia/}, {@code www.бренд.com}; «A &amp; B» — {@code www.aandb.sk}, {@code www.aandb.com}.
     *
     * @param companyName название компании
     * @return адреса по порядку проверки, без повторов
     */
    static List<SiteAddress> addresses(String companyName) {
        Set<SiteAddress> addresses = new LinkedHashSet<>();
        for (String word : asciiWords(companyName)) {
            if (word.endsWith(".sk") && word.length() > NAME_DOMAIN_MIN) {
                addresses.add(new SiteAddress(WWW + word, ""));
            }
        }
        for (String brand : brands(companyName)) {
            for (String host : List.of(brand + ".sk", brand + "-slovakia.sk", brand + "slovakia.sk")) {
                addresses.add(new SiteAddress(WWW + host, ""));
            }
            for (String path : List.of("sk/", "slovakia/", "")) {
                addresses.add(new SiteAddress(WWW + brand + ".com", path));
            }
            for (String host : List.of(brand + "slovensko.sk", brand + "-slovensko.sk", brand + "-jobs.sk",
                    brand + "jobs.sk", brand + "-kariera.sk")) {
                addresses.add(new SiteAddress(WWW + host, ""));
            }
            addresses.add(new SiteAddress("kariera." + brand + ".sk", ""));
        }
        Matcher pair = PAIR.matcher(companyName);
        if (pair.lookingAt()) {
            String joined = (pair.group(1) + "and" + pair.group(2)).toLowerCase(Locale.ROOT);
            addresses.add(new SiteAddress(WWW + joined + ".sk", ""));
            addresses.add(new SiteAddress(WWW + joined + ".com", ""));
        }
        return new ArrayList<>(addresses);
    }

    /**
     * Угаданный адрес, ответ 403 которого — кандидат {@code HTTP_403}: {@code www.бренд.sk} или
     * {@code www.бренд.com}, бренд — не общее слово.
     *
     * @param host        хост
     * @param companyName название компании
     * @return главный адрес бренда ли это
     */
    static boolean brandHome(String host, String companyName) {
        return brands(companyName).stream().filter(brand -> !GENERIC.contains(brand))
                .anyMatch(brand -> host.equals(WWW + brand + ".sk") || host.equals(WWW + brand + ".com"));
    }

    /**
     * Слова названия латиницей без диакритики, без правовой формы и связок (a, the, and); точки внутри слова
     * сохраняются ({@code firma.sk}).
     */
    private static List<String> asciiWords(String companyName) {
        String ascii = Normalizer.normalize(companyName, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        for (String word : LEGAL_FORMS.matcher(ascii).replaceAll(" ").split("[^a-z0-9.]+")) {
            if (!word.isEmpty() && !SKIPPED_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    /**
     * Регистрируемый домен хоста: {@code firma.sk}, {@code firma.com}, {@code firma.co.kr} (второй уровень co, com,
     * org, net, ac в двухбуквенной зоне — три последние части).
     *
     * @param host хост
     * @return домен в нижнем регистре
     */
    static String registrable(String host) {
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        int count = labels.length;
        if (count >= 3 && SECOND_LEVEL.contains(labels[count - 2]) && labels[count - 1].length() == 2) {
            return String.join(".", labels[count - 3], labels[count - 2], labels[count - 1]);
        }
        return count >= 2 ? labels[count - 2] + "." + labels[count - 1] : host.toLowerCase(Locale.ROOT);
    }

    private static String ascii(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
    }

    private static boolean parked(String html) {
        return PARKED.matcher(html.substring(0, Math.min(html.length(), RAW_SCAN_LIMIT))).find();
    }

    private static String firstLabel(String host) {
        String domain = host.startsWith(WWW) ? host.substring(WWW.length()) : host;
        return domain.replace("-", "").split("\\.")[0];
    }

    private static boolean matchesDomain(String key, String host) {
        String first = firstLabel(host);
        return key.equals(first) || key.length() >= SHORT_BRAND_MAX && first.contains(key);
    }

    private static boolean slovakSignal(String location, String text) {
        if (SLOVAK_SIGNAL.matcher(text).find()) {
            return true;
        }
        String path = location == null ? "" : location.replaceFirst("^[a-z]+://[^/]*", "").replaceFirst("[?#].*$", "");
        for (String segment : path.toLowerCase(Locale.ROOT).split("/")) {
            if (SLOVAK_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Бренд отдельным словом.
     */
    private static Pattern word(String brand) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(brand) + "(?![\\p{L}\\p{N}])");
    }
}
