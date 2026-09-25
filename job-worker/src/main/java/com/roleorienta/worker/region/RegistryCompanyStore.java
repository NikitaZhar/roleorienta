package com.roleorienta.worker.region;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Компании региона из реестра юрлиц (V32, §95): импорт и итог проверки пути до карьерной страницы.
 */
@Repository
public class RegistryCompanyStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к общей БД
     */
    public RegistryCompanyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Компания реестра.
     *
     * @param country  страна (ISO 3166-1 alpha-2)
     * @param registry реестр
     * @param registryId номер юрлица в реестре
     * @param name     название
     * @param details  город и основной вид деятельности
     */
    public record Imported(String country, String registry, String registryId, String name, Details details) {
    }

    /**
     * Город и основной вид деятельности.
     *
     * @param municipality город
     * @param activityCode код вида деятельности
     * @param activity     название вида деятельности
     */
    public record Details(String municipality, String activityCode, String activity) {
    }

    /**
     * Компания к проверке.
     *
     * @param id         строка
     * @param registryId номер юрлица (IČO) — для подтверждения сайта
     * @param name       название — для кандидатов домена
     */
    public record Pending(long id, String registryId, String name) {
    }

    /**
     * Добавляет компанию; уже известную (страна, реестр, номер) не трогает.
     *
     * @param company компания
     * @return {@code true}, если добавлена впервые
     */
    public boolean add(Imported company) {
        return jdbcTemplate.update(
                "INSERT INTO registry_company (country, registry, registry_id, name, municipality, activity_code, activity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (country, registry, registry_id) DO NOTHING",
                company.country(), company.registry(), company.registryId(), company.name(),
                company.details().municipality(), company.details().activityCode(), company.details().activity()) == 1;
    }

    /**
     * Известна ли компания.
     *
     * @param country    страна
     * @param registry   реестр
     * @param registryId номер юрлица
     * @return {@code true}, если строка есть
     */
    public boolean exists(String country, String registry, String registryId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM registry_company WHERE country = ? AND registry = ? AND registry_id = ?",
                Integer.class, country, registry, registryId);
        return count != null && count > 0;
    }

    /**
     * Сколько компаний страны в выборке.
     *
     * @param country страна
     * @return число строк
     */
    public int count(String country) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM registry_company WHERE country = ?", Integer.class, country);
        return count == null ? 0 : count;
    }

    /**
     * Ещё не проверенные компании в порядке импорта.
     *
     * @param limit бюджет прохода
     * @return компании
     */
    public List<Pending> unchecked(int limit) {
        return jdbcTemplate.query(
                "SELECT id, registry_id, name FROM registry_company WHERE checked_at IS NULL ORDER BY id LIMIT ?",
                (rs, rowNum) -> new Pending(rs.getLong(1), rs.getString(2), rs.getString(3)), limit);
    }

    /**
     * Записывает итог проверки.
     *
     * @param id     строка
     * @param result итог
     */
    public void recordCheck(long id, CheckResult result) {
        CareerPage career = result.career();
        jdbcTemplate.update(
                "UPDATE registry_company SET website = ?, career_url = ?, career_system = ?, posting_count = ?, "
                        + "niche_count = ?, check_note = ?, checked_at = now(), updated_at = now() WHERE id = ?",
                result.website(), career == null ? null : career.url(), career == null ? null : career.system(),
                career == null ? null : career.postings(), career == null ? null : career.nichePostings(),
                result.note(), id);
    }
}
