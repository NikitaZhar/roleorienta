package com.roleorienta.worker.site;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *   <li>бренд совпадает с первым словом домена (или, от трёх букв, содержится в нём) и есть отдельным
 *       словом в видимом тексте; бренд до трёх букв — ещё и в заголовке {@code title}.</li>
 * </ul>
 *
 * <p>Правила перенесены из скрипта замера {@code survey/site-search.py} (стенограмма §48).</p>
 */
final class SiteBrand {

    /** Меньше слов в видимом тексте — заглушка или пустая страница. */
    static final int MIN_WORDS = 30;

    private static final int SHORT_BRAND_MAX = 3;
    private static final int RAW_SCAN_LIMIT = 300_000;
    private static final Pattern LEGAL_FORMS = Pattern.compile(
            "\\b(s\\.?\\s?r\\.?\\s?o\\.?|a\\.?\\s?s\\.?|spol\\.?\\s*s\\s*r\\.?\\s?o\\.?|v\\.?\\s?o\\.?\\s?s\\.?|"
            + "k\\.?\\s?s\\.?|se|organizacna zlozka.*|pobocka.*|slovenska republika|slovakia|slovensko|sr|"
            + "spolocnost s rucenim obmedzenym|akciova spolocnost)\\b");
    private static final Pattern PARKED = Pattern.compile("(?i)domain (is )?for sale|dom[eé]na je na predaj|"
            + "buy this domain|parked|hugedomains|sedo\\.com|afternic|dan\\.com|godaddy|domain_profile");
    private static final Pattern SLOVAK_SIGNAL = Pattern.compile("(?iu)slovensk|\\bslovakia\\b|\\bslovak republic\\b");
    private static final Set<String> SLOVAK_SEGMENTS = Set.of("sk", "sk-sk", "sk_sk", "slovakia");
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
     * @param host        хост сайта (по нему — первое слово домена и зона)
     * @param page        разобранная страница; её адрес — для сегмента пути {@code /sk/}
     * @param html        исходный текст страницы — для признаков заглушки
     * @param companyName название компании из реестра
     * @return подтверждает ли бренд сайт
     */
    static boolean confirmed(String host, Document page, String html, String companyName) {
        String text = page.text();
        if (PARKED.matcher(html.substring(0, Math.min(html.length(), RAW_SCAN_LIMIT))).find()
                || text.split("\\s+").length < MIN_WORDS) {
            return false;
        }
        String plainHost = host.toLowerCase(Locale.ROOT);
        if (!plainHost.endsWith(".sk") && !slovakSignal(page.location(), text)) {
            return false;
        }
        List<String> inText = new ArrayList<>();
        for (String key : keys(companyName)) {
            if (matchesDomain(key, plainHost) && word(key).matcher(text).find()) {
                inText.add(key);
            }
        }
        if (inText.isEmpty()) {
            return false;
        }
        if (inText.stream().allMatch(key -> key.length() <= SHORT_BRAND_MAX)) {
            return inText.stream().anyMatch(key -> word(key).matcher(page.title()).find());
        }
        return true;
    }

    /**
     * Бренды названия без дефиса: первое слово, первые два слитно; общие слова и слова короче двух букв
     * отброшены.
     */
    static Set<String> keys(String companyName) {
        String ascii = Normalizer.normalize(companyName, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase(Locale.ROOT);
        List<String> clean = new ArrayList<>();
        for (String word : LEGAL_FORMS.matcher(ascii).replaceAll(" ").split("[^a-z0-9.]+")) {
            String stripped = word.replaceAll("^\\.+|\\.+$", "");
            if (!stripped.isEmpty() && !SKIPPED_WORDS.contains(word) && !word.endsWith(".sk")) {
                clean.add(stripped);
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        if (!clean.isEmpty()) {
            keys.add(clean.get(0));
            if (clean.size() > 1) {
                keys.add(clean.get(0) + clean.get(1));
            }
        }
        keys.removeIf(key -> key.length() < 2 || GENERIC.contains(key));
        return keys;
    }

    private static boolean matchesDomain(String key, String host) {
        String domain = host.startsWith("www.") ? host.substring("www.".length()) : host;
        String first = domain.replace("-", "").split("\\.")[0];
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

    private static Pattern word(String key) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(key) + "(?![\\p{L}\\p{N}])");
    }
}
