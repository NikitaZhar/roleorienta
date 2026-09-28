package com.roleorienta.worker.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Разбор robots.txt и сопоставление путей по RFC 9309.
 */
class RobotsRulesTests {

    private static final String TOKEN = "Roleorienta";

    /**
     * Группа с токеном робота важнее группы {@code *}; регистр токена не важен.
     */
    @Test
    void ownGroupOverridesWildcard() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /

                User-agent: roleorienta
                Disallow: /private
                """, TOKEN);

        assertThat(rules.allows("/jobs")).isTrue();
        assertThat(rules.allows("/private/1")).isFalse();
    }

    /**
     * Самое длинное совпадение побеждает; при равной длине — {@code allow}; комментарии и пустой
     * {@code disallow} не влияют.
     */
    @Test
    void longestMatchWinsAndAllowWinsTie() {
        RobotsRules rules = RobotsRules.parse("""
                # comment
                User-agent: *
                Disallow: /jobs
                Allow: /jobs/open   # comment
                Allow: /same
                Disallow: /same
                Disallow:
                """, TOKEN);

        assertThat(rules.allows("/jobs/closed")).isFalse();
        assertThat(rules.allows("/jobs/open/1")).isTrue();
        assertThat(rules.allows("/same")).isTrue();
        assertThat(rules.allows("/other")).isTrue();
    }

    /**
     * {@code *} — любая последовательность, {@code $} — конец пути.
     */
    @Test
    void supportsWildcardAndEndAnchor() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /*.pdf$
                Disallow: /search*page=
                """, TOKEN);

        assertThat(rules.allows("/files/cv.pdf")).isFalse();
        assertThat(rules.allows("/files/cv.pdf?download=1")).isTrue();
        assertThat(rules.allows("/search?q=java&page=2")).isFalse();
        assertThat(rules.allows("/search?q=java")).isTrue();
    }

    /**
     * Строки {@code user-agent} подряд образуют одну группу; новая строка после правил начинает
     * новую группу.
     */
    @Test
    void groupsAgentsAndSplitsGroups() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: other
                User-agent: Roleorienta
                Disallow: /a
                User-agent: other
                Disallow: /b
                """, TOKEN);

        assertThat(rules.allows("/a")).isFalse();
        assertThat(rules.allows("/b")).isTrue();
    }
}
