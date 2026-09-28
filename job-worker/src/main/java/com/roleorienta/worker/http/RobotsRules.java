package com.roleorienta.worker.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Правила robots.txt для одного робота (RFC 9309, https://www.rfc-editor.org/rfc/rfc9309).
 *
 * <ul>
 *   <li>Группа — подряд идущие строки {@code user-agent} и правила после них. Берутся группы с
 *       токеном робота (без учёта регистра), а если таких нет — группы {@code *}; совпавшие группы
 *       объединяются.</li>
 *   <li>Из совпавших с путём правил побеждает самое длинное; при равной длине — {@code allow}. Нет
 *       совпадений — разрешено.</li>
 *   <li>{@code *} — любая последовательность, {@code $} в конце — конец пути. Сопоставление без
 *       регулярных выражений: чужой файл не может вызвать экспоненциальный перебор.</li>
 * </ul>
 */
public final class RobotsRules {

    /** Разрешено всё: robots.txt нет (4xx) или он пуст. */
    public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());

    private static final String WILDCARD_AGENT = "*";
    private static final char ANY = '*';
    private static final String END = "$";

    private final List<Rule> rules;

    private RobotsRules(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * @param body         текст robots.txt
     * @param productToken токен робота из User-Agent, например {@code Roleorienta}
     * @return правила для робота
     */
    public static RobotsRules parse(String body, String productToken) {
        List<Rule> ownRules = new ArrayList<>();
        List<Rule> wildcardRules = new ArrayList<>();
        List<String> groupAgents = new ArrayList<>();
        boolean groupHasRules = false;
        for (String rawLine : body.split("\\R")) {
            int comment = rawLine.indexOf('#');
            String line = (comment >= 0 ? rawLine.substring(0, comment) : rawLine).strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if ("user-agent".equals(key)) {
                if (groupHasRules) {
                    groupAgents.clear();
                    groupHasRules = false;
                }
                groupAgents.add(value.toLowerCase(Locale.ROOT));
            } else if (("allow".equals(key) || "disallow".equals(key)) && !groupAgents.isEmpty()) {
                groupHasRules = true;
                if (value.isEmpty()) {
                    continue;
                }
                Rule rule = new Rule("allow".equals(key), value);
                if (groupAgents.contains(productToken.toLowerCase(Locale.ROOT))) {
                    ownRules.add(rule);
                }
                if (groupAgents.contains(WILDCARD_AGENT)) {
                    wildcardRules.add(rule);
                }
            }
        }
        return new RobotsRules(List.copyOf(ownRules.isEmpty() ? wildcardRules : ownRules));
    }

    /**
     * @param path путь с запросом, например {@code /jobs?page=2}
     * @return разрешён ли путь
     */
    public boolean allows(String path) {
        Rule best = null;
        for (Rule rule : rules) {
            if (matches(rule.pattern(), path) && (best == null
                    || rule.pattern().length() > best.pattern().length()
                    || rule.pattern().length() == best.pattern().length() && rule.allow())) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    /**
     * Шаблон без {@code $} совпадает с началом пути; линейный перебор с возвратом к последней
     * звёздочке — O(длина шаблона × длина пути).
     */
    private static boolean matches(String pattern, String path) {
        String glob = pattern.endsWith(END) ? pattern.substring(0, pattern.length() - 1) : pattern + ANY;
        int globIndex = 0;
        int pathIndex = 0;
        int starIndex = -1;
        int resumeIndex = 0;
        while (pathIndex < path.length()) {
            if (globIndex < glob.length() && glob.charAt(globIndex) == ANY) {
                starIndex = globIndex++;
                resumeIndex = pathIndex;
            } else if (globIndex < glob.length() && glob.charAt(globIndex) == path.charAt(pathIndex)) {
                globIndex++;
                pathIndex++;
            } else if (starIndex >= 0) {
                globIndex = starIndex + 1;
                pathIndex = ++resumeIndex;
            } else {
                return false;
            }
        }
        while (globIndex < glob.length() && glob.charAt(globIndex) == ANY) {
            globIndex++;
        }
        return globIndex == glob.length();
    }

    /**
     * @param allow   {@code allow} или {@code disallow}
     * @param pattern шаблон пути
     */
    private record Rule(boolean allow, String pattern) {
    }
}
