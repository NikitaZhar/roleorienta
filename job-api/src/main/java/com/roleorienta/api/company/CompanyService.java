package com.roleorienta.api.company;

import com.roleorienta.api.company.CompanyDtos.CareerRequestItem;
import com.roleorienta.api.company.CompanyDtos.CompanyItem;
import com.roleorienta.api.company.CompanyDtos.CompanyPage;
import com.roleorienta.api.condition.InvalidFieldException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Компании по группам и адреса кадровых страниц, введённые пользователями (§137; бизнес-описание §4.2, §5): поиск,
 * сомнительные компании, ввод адреса с проверкой и ограничением частоты, запросы пользователя. Проверку адреса делает
 * job-worker (задание {@code CHECK_CAREER_URL}); добавленный адрес общий для всех пользователей.
 */
@Service
public class CompanyService {

    /** Группа: кадровая страница читается. */
    static final String GROUP_YES = "YES";

    /** Группа: отметка администратора «не работодатель» (§138). */
    static final String GROUP_NOT_EMPLOYER = "NOT_EMPLOYER";

    /** Строк страницы сомнительных компаний по умолчанию. */
    public static final int DEFAULT_LIMIT = 50;

    /** Строк страницы не больше. */
    static final int MAX_LIMIT = 100;

    /** Символов в запросе поиска не меньше. */
    static final int MIN_QUERY = 3;

    /** Запросов пользователя в списке не больше. */
    static final int REQUESTS_SHOWN = 100;

    private static final Pattern NUMBER = Pattern.compile("\\d{6,12}");
    private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9]+");

    private final CompanyRepository repository;
    private final CompanyProperties properties;

    /**
     * @param repository компании и запросы
     * @param properties ограничение частоты
     */
    public CompanyService(CompanyRepository repository, CompanyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * @param userId пользователь — его скрытые компании не ищутся; {@code 0} — все (администратор)
     * @param query  название (часть, от {@value #MIN_QUERY} символов, без учёта регистра и диакритики) или IČO
     * @return найденные компании; {@code 400} — запрос короче
     */
    @Transactional(readOnly = true)
    public List<CompanyItem> search(long userId, String query) {
        String trimmed = query == null ? "" : query.strip();
        String slug = slug(trimmed);
        if (slug.length() < MIN_QUERY) {
            throw new InvalidFieldException("/query", "At least " + MIN_QUERY + " letters or digits");
        }
        return repository.search(userId, NUMBER.matcher(trimmed).matches() ? trimmed : null, slug);
    }

    /**
     * @param userId пользователь — его скрытые компании не показываются
     * @param after  курсор: id последней компании прежней страницы; {@code null} — первая
     * @param limit  строк (1–{@value #MAX_LIMIT})
     * @return сомнительные компании
     */
    @Transactional(readOnly = true)
    public CompanyPage doubtful(long userId, Long after, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<CompanyItem> rows = repository.doubtful(userId, after == null ? 0 : after, size + 1);
        boolean more = rows.size() > size;
        List<CompanyItem> items = more ? rows.subList(0, size) : rows;
        return new CompanyPage(items, more ? items.get(items.size() - 1).id() : null);
    }

    /**
     * Ввод адреса кадровой страницы компании из групп «может быть» и «нет».
     *
     * @param userId    пользователь
     * @param companyId компания
     * @param url       адрес
     * @return запрос «в обработке»; {@code 404} — компании нет; {@code 409} — кадровая страница уже читается или
     *         компания отмечена «не работодатель»;
     *         {@code 400} — адрес не http(s); {@code 429} — больше {@link CompanyProperties#dailyLimit()} адресов
     *         за сутки
     */
    @Transactional
    public CareerRequestItem addCareerPage(long userId, long companyId, String url) {
        CompanyItem company = repository.find(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
        if (GROUP_YES.equals(company.check().group())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Career page of the company is already read");
        }
        if (GROUP_NOT_EMPLOYER.equals(company.check().group())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The company is marked as not an employer");
        }
        String address = url.strip();
        if (!webAddress(address)) {
            throw new InvalidFieldException("/url", "An http(s) address is required");
        }
        if (repository.requestsLastDay(userId) >= properties.dailyLimit()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "No more than " + properties.dailyLimit() + " addresses a day");
        }
        return repository.addRequest(userId, companyId, address);
    }

    /**
     * @param userId пользователь
     * @return его запросы, новые первыми
     */
    @Transactional(readOnly = true)
    public List<CareerRequestItem> requests(long userId) {
        return repository.requests(userId, REQUESTS_SHOWN);
    }

    /**
     * Личная отметка «скрыть» (§138): компания пропадает из поиска и сомнительных только у этого пользователя.
     *
     * @param userId    пользователь
     * @param companyId компания
     * @param hidden    скрыть или вернуть
     * @throws ResponseStatusException {@code 404} — компании нет
     */
    @Transactional
    public void hide(long userId, long companyId, boolean hidden) {
        repository.find(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
        if (hidden) {
            repository.hide(userId, companyId);
        } else {
            repository.unhide(userId, companyId);
        }
    }

    /**
     * @param userId пользователь
     * @return компании, которые он скрыл
     */
    @Transactional(readOnly = true)
    public List<CompanyItem> hidden(long userId) {
        return repository.hidden(userId);
    }

    /**
     * Отметка администратора «не работодатель» (§138): общая для всех.
     *
     * @param companyId   компания
     * @param notEmployer поставить или снять
     * @throws ResponseStatusException {@code 404} — компании нет
     */
    @Transactional
    public void markNotEmployer(long companyId, boolean notEmployer) {
        if (!repository.markNotEmployer(companyId, notEmployer)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found");
        }
    }

    /**
     * @param text название
     * @return вид {@code company.name_slug}: без диакритики, нижний регистр, не буквы и не цифры — дефис
     */
    static String slug(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return NOT_SLUG.matcher(plain).replaceAll("-").replaceAll("^-+|-+$", "");
    }

    private static boolean webAddress(String url) {
        try {
            URI uri = new URI(url);
            return uri.getHost() != null && uri.getScheme() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (URISyntaxException malformed) {
            return false;
        }
    }
}
