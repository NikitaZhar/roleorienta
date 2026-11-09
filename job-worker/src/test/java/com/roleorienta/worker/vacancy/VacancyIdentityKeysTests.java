package com.roleorienta.worker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ключи опознания вакансии (V102; docs/vacancy-identity-plan.md §4, срез 1; стенограмма §278) на названиях, местах и
 * адресах пар выборки {@code target/s276-sample.txt}: пары «одна вакансия» дают общий ключ, пары «разные» — нет, в том
 * числе ловушки (место в скобках названия, кириллица, общее место, края, «Obec - Okres», районы Bratislava, улица с
 * номером дома). Ключи у публикации считает сам PostgreSQL (вычисляемые колонки). Тест в транзакции с откатом.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class VacancyIdentityKeysTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void sameVacancyTitlesGiveOneKeyAndPlacesInBracketsKeepTitlesApart() {
        // одна: правовая форма в скобках (пара 129), пометки пола (135), тире (101), регистр (90)
        assertThat(titleKey("Tesár / Betonár / Pomocný stavebný pracovník – TOBI TEAN s.r.o. (muž/žena)"))
                .isEqualTo(titleKey("Tesár / Betonár / Pomocný stavebný pracovník – TOBI TEAN s.r.o. "
                        + "(TOBI TEAN s.r.o.) (muž/žena)"));
        assertThat(titleKey("Skladník (m/ž)")).isEqualTo(titleKey("Skladník m/ž (muž/žena)")).isEqualTo("skladnik");
        assertThat(titleKey("Zubný asistent - stomatologicá ambulancia -ProCare Betliarska - Bratislava (m/ž)"))
                .isEqualTo(titleKey("Zubný asistent – stomatologicá ambulancia -ProCare Betliarska – Bratislava (m/ž)"));
        assertThat(titleKey("Software Developer In Security (m/f)"))
                .isEqualTo(titleKey("Software Developer in Security"));
        // разные: место или вид работы в скобках (пары 46, 49, 70, 124)
        assertThat(titleKey("Pracovník prevádzky na skrátený úväzok (m/ž) - obchodný dom v Košiciach (Popradská)"))
                .isNotEqualTo(titleKey("Pracovník prevádzky na skrátený úväzok (m/ž) - obchodný dom v Košiciach "
                        + "(Nad jazerom)"));
        assertThat(titleKey("Vedúca / Vedúci maloobchodnej predajne TEDi - Bratislava (STOP SHOP) (muž/žena)"))
                .isNotEqualTo(titleKey("Vedúca / Vedúci maloobchodnej predajne TEDi - Bratislava (Shopping Palace)"));
        assertThat(titleKey("Language Alignment & Resource Partner (Azerbaijani) - Freelance AI Trainer Project"))
                .isNotEqualTo(titleKey(
                        "Language Alignment & Resource Partner (Latvian) - Freelance AI Trainer Project"));
        assertThat(titleKey("IT Metodik/Metodička (muž/žena)"))
                .isNotEqualTo(titleKey("IT Metodik/Metodička (ITIL) (muž/žena)"));
    }

    @Test
    void cyrillicTitleIsTransliteratedNotDropped() {
        // same_title оставлял от таких названий цифры и латинские обрывки — разные объявления совпадали (§275)
        assertThat(titleKey("Водій/ка NZV з категорією С з зп від 1550 євро. Житло та транспорт (muž/žena)"))
                .startsWith("vodiy ka nzv z kategoriyeyu");
        assertThat(titleKey("Оператор на производство пластиковых деталей в Нитре (muž/žena)"))
                .isEqualTo("operator na proizvodstvo plastikovykh detaley v nitre");
    }

    @Test
    void placesAreCitiesAndRegionsAndGeneralPlacesAreNone() {
        // «Obec - Okres» и «okres X» (пара 51), почтовый индекс и улица (58), скобки (95), районы Bratislava (133)
        assertThat(places("Topoľčany - Topoľčany")).containsExactly("c:topolcany");
        assertThat(places("okres Topolčany")).containsExactly("c:topolcany");
        assertThat(places("Obchodná 2889/8, 080 06 Prešov, Prešov")).containsExactly("c:presov");
        assertThat(places("Hlavná 19, Hrubý Šúr, Hrubý Šúr")).containsExactly("c:hruby sur");
        assertThat(places("Senec (Logistické centrum)")).isEqualTo(places("Senec - Senec")).containsExactly("c:senec");
        assertThat(places("Bratislava-Záhor.Bystr. - Bratislava IV")).isEqualTo(places("Bratislava I"))
                .containsExactly("c:bratislava");
        assertThat(places("Košice-Staré Mesto - Košice I")).containsExactly("c:kosice");
        assertThat(places("Košice; Bratislava; Žilina")).containsExactly("c:bratislava", "c:kosice", "c:zilina");
        assertThat(places("Šahy, Nitiriansky kraj, SK, 936 01")).containsExactly("c:sahy", "r:nitiriansky kraj");
        assertThat(places("Bratislavský kraj, Trnavský kraj (Práca vyžaduje cestovanie)"))
                .containsExactly("r:bratislavsky kraj", "r:trnavsky kraj");
        // разные населённые пункты одного района (пары 147, 148)
        assertThat(places("Ivanka pri Dunaji - Senec")).containsExactly("c:ivanka pri dunaji");
        assertThat(places("Demänovská Dolina - Liptovský Mikuláš")).containsExactly("c:demanovska dolina");
        // общее место — мест нет
        for (String general : List.of("- Slovensko", "Slovakia", "Práca z domu", "Home office", "World Wide - Remote",
                "Slovenská republika, Slovensko (Práca vyžaduje cestovanie)", "")) {
            assertThat(places(general)).as(general).isEmpty();
        }
    }

    @Test
    void profesiaNumberAndAddressIgnoreSourceAndSessionParameters() {
        String first = "https://ptacek.profesia.sk/praca/ptacek-velkoobchod/O5371567?search_id=df11dc59";
        String second = "https://ptacek.profesia.sk/praca/ptacek-velkoobchod/O5371567?search_id=4bbb44bf";
        assertThat(text("SELECT clean_address(?)", first)).isEqualTo(text("SELECT clean_address(?)", second));
        assertThat(text("SELECT clean_address(?)", "https://alanata.nalgoo-jobs.com/jobs/93096?register=1&source_id=2"))
                .isEqualTo("alanata.nalgoo-jobs.com/jobs/93096");
        assertThat(jdbcTemplate.queryForObject("SELECT profesia_number(?, ?)", String.class,
                "profesia:5383932", "https://www.profesia.sk/O5383932")).isEqualTo("5383932");
        assertThat(jdbcTemplate.queryForObject("SELECT profesia_number(?, ?)", String.class,
                "https://www.profesia.sk/praca/trago/O5383932", "https://www.profesia.sk/praca/trago/O5383932"))
                .isEqualTo("5383932");
        assertThat(jdbcTemplate.queryForObject("SELECT profesia_number(?, ?)", String.class, first, first))
                .isEqualTo("5371567");
        assertThat(jdbcTemplate.queryForObject("SELECT profesia_number(?, ?)", String.class,
                "/job/Bratislava/AI-Solutions-Architect_R00333397", "https://accenture.wd103.myworkdayjobs.com/x"))
                .isNull();
    }

    @Test
    void postingKeysAreComputedByDatabase() {
        long source = jdbcTemplate.queryForObject(
                "INSERT INTO source (provider, board) VALUES ('careerpage', 'keys-test') RETURNING id", Long.class);
        long vacancy = jdbcTemplate.queryForObject("INSERT INTO vacancy (state, title, primary_url, first_seen_at, "
                + "last_confirmed_at) VALUES ('ACTIVE', 'Skladník', 'u', now(), now()) RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO job_posting (source_id, vacancy_id, external_id, title, url, location, "
                + "first_seen_at, last_confirmed_at) VALUES (?, ?, 'O5371567', 'Skladník (m/ž)', ?, 'Senec - Senec', "
                + "now(), now())", source, vacancy, "https://ptacek.profesia.sk/praca/x/O5371567?search_id=1");
        Map<String, Object> keys = jdbcTemplate.queryForMap("SELECT title_key, place_keys::text AS places, "
                + "profesia_num, address_key FROM job_posting WHERE source_id = ?", source);
        assertThat(keys).containsEntry("title_key", "skladnik").containsEntry("places", "{c:senec}")
                .containsEntry("profesia_num", "5371567")
                .containsEntry("address_key", "ptacek.profesia.sk/praca/x/o5371567");
    }

    private String titleKey(String title) {
        return text("SELECT vacancy_title_key(?)", title);
    }

    private List<String> places(String location) {
        return jdbcTemplate.queryForObject("SELECT vacancy_places(?)", (row, number) -> strings(row.getArray(1)),
                location);
    }

    private String text(String sql, String value) {
        return jdbcTemplate.queryForObject(sql, String.class, value);
    }

    private static List<String> strings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }
}
