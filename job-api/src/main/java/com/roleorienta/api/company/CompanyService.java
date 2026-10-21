package com.roleorienta.api.company;

import com.roleorienta.api.company.CompanyDtos.CareerRequestItem;
import com.roleorienta.api.company.CompanyDtos.CompanyItem;
import com.roleorienta.api.company.CompanyDtos.CompanyPage;
import com.roleorienta.api.company.CompanyRepository.DoubtfulPage;
import com.roleorienta.api.company.CompanyRuleException.Kind;
import com.roleorienta.api.condition.InvalidFieldException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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

    /** Строк страницы сомнительных компаний по умолчанию (как у результатов поиска, экран предлагает 20, 50, 100). */
    public static final int DEFAULT_SIZE = 20;

    /** Строк страницы не больше. */
    static final int MAX_SIZE = 100;

    /** Символов в запросе поиска не меньше. */
    static final int MIN_QUERY = 3;

    /** Запросов пользователя в списке не больше. */
    static final int REQUESTS_SHOWN = 100;

    private static final Pattern NUMBER = Pattern.compile("\\d{6,12}");
    private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final String COMPANY_NOT_FOUND = "Company not found";

    private final CompanyRepository repository;
    private final CompanyProperties properties;

    /**
     * @param repository компании и запросы
     * @param properties ограничение частоты, порог сотрудников признака найма
     */
    public CompanyService(CompanyRepository repository, CompanyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * @param userId пользователь — его скрытые компании не ищутся
     * @param query  название (часть, от {@value #MIN_QUERY} символов, без учёта регистра и диакритики) или IČO
     * @return найденные компании; {@code 400} — запрос короче
     */
    @Transactional(readOnly = true)
    public List<CompanyItem> search(long userId, String query) {
        String trimmed = trimmedQuery(query);
        return repository.search(userId, number(trimmed), slug(trimmed));
    }

    /**
     * Поиск администратора — без личных скрытий пользователей.
     *
     * @param query название (часть) или IČO
     * @return найденные компании; {@code 400} — запрос короче {@value #MIN_QUERY}
     */
    @Transactional(readOnly = true)
    public List<CompanyItem> searchAll(String query) {
        String trimmed = trimmedQuery(query);
        return repository.searchAll(number(trimmed), slug(trimmed));
    }

    /**
     * Страница и общее число — одним снимком данных ({@code REPEATABLE READ}, как списки вакансий); номер меньше 1 —
     * первая страница, размер обрезается до 1…{@value #MAX_SIZE}; страница за концом — пустая, с общим числом.
     *
     * @param userId пользователь — его скрытые компании не показываются
     * @param page   номер страницы, с 1
     * @param size   строк на странице
     * @return страница сомнительных компаний по id и общее число
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompanyPage doubtful(long userId, int page, int size) {
        int number = Math.max(1, page);
        int rows = Math.max(1, Math.min(size, MAX_SIZE));
        DoubtfulPage window = new DoubtfulPage(userId, (long) (number - 1) * rows, rows,
                properties.hiringMinEmployees());
        return new CompanyPage(repository.doubtful(window), number, rows, repository.countDoubtful(window));
    }

    /**
     * Ввод адреса кадровой страницы компании без своей читаемой кадровой страницы: «только портал», «может быть»,
     * «нет». Подсчёт за сутки и вставка — под блокировкой пользователя (параллельные запросы лимит не обходят).
     *
     * @param userId    пользователь
     * @param companyId компания
     * @param url       адрес
     * @return запрос «в обработке»
     * @throws CompanyRuleException {@code NOT_FOUND} — компании нет; {@code CONFLICT} — кадровая страница уже
     *                              читается, компания отмечена «не работодатель» или этот адрес уже проверяется;
     *                              {@code TOO_MANY} — больше {@link CompanyProperties#dailyLimit()} адресов за сутки
     * @throws InvalidFieldException адрес не http(s)
     */
    @Transactional
    public CareerRequestItem addCareerPage(long userId, long companyId, String url) {
        CompanyItem company = existing(companyId);
        if (GROUP_YES.equals(company.check().group())) {
            throw new CompanyRuleException(Kind.CONFLICT, "Career page of the company is already read");
        }
        if (GROUP_NOT_EMPLOYER.equals(company.check().group())) {
            throw new CompanyRuleException(Kind.CONFLICT, "The company is marked as not an employer");
        }
        String address = url.strip();
        if (!webAddress(address)) {
            throw new InvalidFieldException("/url", "An http(s) address is required");
        }
        repository.lockRequests(userId);
        if (repository.pendingExists(userId, companyId, address)) {
            throw new CompanyRuleException(Kind.CONFLICT, "This address is already being checked");
        }
        if (repository.requestsLastDay(userId) >= properties.dailyLimit()) {
            throw new CompanyRuleException(Kind.TOO_MANY,
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
     * @throws CompanyRuleException {@code NOT_FOUND} — компании нет
     */
    @Transactional
    public void hide(long userId, long companyId, boolean hidden) {
        existing(companyId);
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
     * @throws CompanyRuleException {@code NOT_FOUND} — действующей компании нет
     */
    @Transactional
    public void markNotEmployer(long companyId, boolean notEmployer) {
        if (!repository.markNotEmployer(companyId, notEmployer)) {
            throw new CompanyRuleException(Kind.NOT_FOUND, COMPANY_NOT_FOUND);
        }
    }

    private CompanyItem existing(long companyId) {
        return repository.find(companyId).orElseThrow(() -> new CompanyRuleException(Kind.NOT_FOUND,
                COMPANY_NOT_FOUND));
    }

    private static String trimmedQuery(String query) {
        String trimmed = query == null ? "" : query.strip();
        if (slug(trimmed).length() < MIN_QUERY) {
            throw new InvalidFieldException("/query", "At least " + MIN_QUERY + " letters or digits");
        }
        return trimmed;
    }

    private static String number(String query) {
        return NUMBER.matcher(query).matches() ? query : null;
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
