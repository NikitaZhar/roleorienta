package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.adapter.stateportal.StatePortalAdapter;
import com.roleorienta.worker.stateportal.ProfesiaOfferPage.Offer;
import com.roleorienta.worker.vacancy.FetchedPosting;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Объявления profesia.sk, которых нет на государственном портале (§219, {@code profesia_offer}, V86), и их источники
 * (провайдер {@link StatePortalAdapter#PROFESIA_PROVIDER}, доска — работодатель в адресе объявления).
 */
@Repository
public class ProfesiaOfferRepository {

    /** Начало внешнего id — как у объявлений profesia.sk общего списка портала ({@code StatePortalAdapter.externalId}). */
    static final String EXTERNAL_ID = "profesia:";

    /**
     * Объявления, которых нет на портале: были в карте сайта последнего чтения (карта читается раз в сутки — окно
     * с запасом 30 ч) и не встречались в общем списке портала за 3 дня (полный обход портала — не дольше 20 ч, лента —
     * каждый час). Объявление, появившееся на портале, выходит отсюда: у источника profesia.sk ему засчитывается
     * отсутствие, вакансия приходит с портала.
     */
    private static final String GAP = """
             FROM profesia_offer o
            WHERE o.listed_at > now() - interval '30 hours'
              AND NOT EXISTS (SELECT 1 FROM portal_offer po WHERE po.external_id = 'profesia:' || o.num
                              AND po.last_seen_at > now() - interval '3 days')""";

    /**
     * Название для сравнения (§220): без регистра, словацкой и чешской диакритики, пометок в скобках («(m/f/d)»,
     * «(muž/žena)») и знаков — «Backend Developer - Java (m/ž)» и «Backend developer – Java» совпадают.
     */
    private static final String SAME_TITLE = "btrim(regexp_replace(regexp_replace(lower(translate(%s, "
            + "'áäčďéěíĺľňóôŕřšťúůýžÁÄČĎÉĚÍĹĽŇÓÔŔŘŠŤÚŮÝŽ', 'aacdeeillnoorrstuuyzaacdeeillnoorrstuuyz')), "
            + "'\\([^)]*\\)', ' ', 'g'), '[^a-z0-9]+', ' ', 'g'))";

    /**
     * Объявление прочитано и той же вакансии у компании нет (§220): у компании работодателя ({@code o.company_id})
     * нет незакрытой публикации с тем же названием в её собственных источниках (кадровая страница, система найма,
     * портал) — «читаем компании, портал и только после этого profesia» (владелец). Работодатель не сопоставлен
     * компании — сравнивать не с чем, объявление берётся.
     */
    private static final String NEW_TO_COMPANY = """
              AND o.title IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM company_source cs
                              JOIN job_posting p ON p.source_id = cs.source_id AND p.closed_at IS NULL
                              WHERE cs.company_id = o.company_id AND %s = %s)"""
            .formatted(SAME_TITLE.formatted("p.title"), SAME_TITLE.formatted("o.title"));

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public ProfesiaOfferRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param limit сколько
     * @return объявления, которых нет на портале, со страницей ещё не прочитанной; новые (больший номер) — первыми
     */
    public List<ListedOffer> toRead(int limit) {
        return jdbcTemplate.query("SELECT o.num, o.employer, o.url" + GAP
                + " AND o.page_read_at IS NULL ORDER BY o.num DESC LIMIT ?",
                (row, number) -> new ListedOffer(row.getLong(1), row.getString(2), row.getString(3)), limit);
    }

    /**
     * @param num   объявление
     * @param offer сведения его страницы
     */
    public void saveRead(long num, Offer offer) {
        jdbcTemplate.update("""
                UPDATE profesia_offer SET page_read_at = now(), title = ?, employer_name = ?, location = ?, content = ?,
                       posted_at = ?
                WHERE num = ?
                """, offer.title(), offer.employerName(), offer.location(), offer.content(),
                offer.postedAt() == null ? null : Timestamp.from(offer.postedAt()), num);
    }

    /**
     * Страницы объявления нет — больше не запрашивается и не записывается (названия нет).
     *
     * @param num объявление
     */
    public void saveGone(long num) {
        jdbcTemplate.update("UPDATE profesia_offer SET page_read_at = now() WHERE num = ?", num);
    }

    /**
     * @return работодатели к записи: с прочитанными объявлениями, которых нет на портале и у компании
     *         ({@link #NEW_TO_COMPANY}), и с источником, у которого
     *         есть незакрытые публикации (им засчитывается отсутствие ушедших)
     */
    public Set<String> employersToRecord() {
        return new LinkedHashSet<>(jdbcTemplate.queryForList("SELECT DISTINCT o.employer" + GAP + NEW_TO_COMPANY
                + " UNION SELECT s.board FROM source s WHERE s.provider = ? AND s.closed_at IS NULL"
                + "   AND EXISTS (SELECT 1 FROM job_posting p WHERE p.source_id = s.id AND p.closed_at IS NULL)",
                String.class, StatePortalAdapter.PROFESIA_PROVIDER));
    }

    /**
     * @param employer работодатель в адресе объявления
     * @return его прочитанные объявления, которых нет на портале и у компании ({@link #NEW_TO_COMPANY}): публикации (ссылка — объявление profesia.sk), даты
     *         размещения и название работодателя из последнего объявления
     */
    public EmployerOffers offersOf(String employer) {
        List<FetchedPosting> postings = new ArrayList<>();
        Map<String, Instant> postedAt = new HashMap<>();
        String[] name = {null};
        jdbcTemplate.query("SELECT o.num, o.title, o.url, o.location, o.content, o.posted_at, o.employer_name" + GAP
                + NEW_TO_COMPANY + " AND o.employer = ? ORDER BY o.num", row -> {
                    String externalId = EXTERNAL_ID + row.getLong(1);
                    postings.add(new FetchedPosting(externalId, row.getString(2), row.getString(3), row.getString(4),
                            row.getString(5)));
                    OffsetDateTime posted = row.getObject(6, OffsetDateTime.class);
                    if (posted != null) {
                        postedAt.put(externalId, posted.toInstant());
                    }
                    if (row.getString(7) != null) {
                        name[0] = row.getString(7);
                    }
                }, employer);
        return new EmployerOffers(postings, postedAt, name[0]);
    }

    /**
     * Источник работодателя: есть — он; нет и {@code create} — создаётся при записанном основании использования
     * ({@code source_permission}). Название работодателя записывается, пока его нет. Компания не связывается (V86):
     * связь включила бы «один канал на компанию» и отключила бы её портал и кадровую страницу.
     *
     * @param employer     работодатель в адресе объявления (доска)
     * @param employerName название из объявления; {@code null} — нет
     * @param create       создавать ли отсутствующий
     * @return id источника; пусто — нет и не создан
     */
    public Optional<Long> source(String employer, String employerName, boolean create) {
        if (create) {
            jdbcTemplate.update("""
                    INSERT INTO source (provider, board, country) SELECT ?, ?, 'SK'
                    WHERE EXISTS (SELECT 1 FROM source_permission WHERE scope = 'PROVIDER' AND provider = ?
                                  AND decision = 'ALLOW')
                    ON CONFLICT (provider, board) DO NOTHING
                    """, StatePortalAdapter.PROFESIA_PROVIDER, employer, StatePortalAdapter.PROFESIA_PROVIDER);
        }
        if (employerName != null) {
            jdbcTemplate.update("UPDATE source SET employer_name = ? WHERE provider = ? AND board = ?"
                    + " AND employer_name IS NULL", employerName, StatePortalAdapter.PROFESIA_PROVIDER, employer);
        }
        return jdbcTemplate.queryForList("SELECT id FROM source WHERE provider = ? AND board = ?", Long.class,
                StatePortalAdapter.PROFESIA_PROVIDER, employer).stream().findFirst();
    }

    /**
     * Объявление карты сайта.
     *
     * @param num      номер ({@code O<номер>} в адресе)
     * @param employer работодатель в адресе
     * @param url      адрес объявления
     */
    public record ListedOffer(long num, String employer, String url) {
    }

    /**
     * Объявления работодателя к записи.
     *
     * @param postings     публикации
     * @param postedAt     даты размещения по внешнему id
     * @param employerName название работодателя; {@code null} — объявления его не дали
     */
    public record EmployerOffers(List<FetchedPosting> postings, Map<String, Instant> postedAt, String employerName) {
    }
}
