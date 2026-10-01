package com.roleorienta.worker.intake;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Компании и курсор приёма реестра (JDBC: пакетная запись порциями по тысяче строк).
 *
 * <p>Курсор сдвигается условным {@code UPDATE … WHERE version = ?}: если версия успела измениться
 * (курсор ведёт другое задание), сдвига нет и бросается {@link OptimisticLockingFailureException} —
 * транзакция порции откатывается целиком.</p>
 */
@Repository
public class IntakeRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate доступ к БД
     */
    public IntakeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param country страна
     * @return курсор; пусто — приём ещё не начинался
     */
    public Optional<IntakeCursor> findCursor(String country) {
        return jdbcTemplate.query("""
                SELECT export_date, file_index, record_offset, daily_date, version
                FROM intake_cursor WHERE country = ?
                """, (row, number) -> new IntakeCursor(row.getObject("export_date", LocalDate.class),
                row.getInt("file_index"), row.getLong("record_offset"),
                row.getObject("daily_date", LocalDate.class), row.getLong("version")), country)
                .stream().findFirst();
    }

    /**
     * Создаёт курсор на начало полной выгрузки; если курсор уже есть — оставляет его.
     *
     * @param country    страна
     * @param registry   реестр
     * @param exportDate дата полной выгрузки
     * @return курсор страны
     */
    public IntakeCursor createCursor(String country, String registry, LocalDate exportDate) {
        jdbcTemplate.update("""
                INSERT INTO intake_cursor (country, registry, export_date, file_index, record_offset)
                VALUES (?, ?, ?, 0, 0) ON CONFLICT (country) DO NOTHING
                """, country, registry, exportDate);
        return findCursor(country).orElseThrow();
    }

    /**
     * Сдвигает курсор.
     *
     * @param country страна
     * @param from    курсор, с которого сдвигаем (его версия проверяется)
     * @param to      новое место (версия не используется)
     * @return новый курсор с версией на единицу больше
     * @throws OptimisticLockingFailureException курсор уже сдвинут другим заданием
     */
    public IntakeCursor moveTo(String country, IntakeCursor from, IntakeCursor to) {
        int updated = jdbcTemplate.update("""
                UPDATE intake_cursor
                SET export_date = ?, file_index = ?, record_offset = ?, daily_date = ?,
                    version = version + 1, updated_at = now()
                WHERE country = ? AND version = ?
                """, to.exportDate(), to.fileIndex(), to.recordOffset(), to.dailyDate(), country, from.version());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Intake cursor of " + country + " moved concurrently");
        }
        return new IntakeCursor(to.exportDate(), to.fileIndex(), to.recordOffset(), to.dailyDate(),
                from.version() + 1);
    }

    /**
     * Порция: компании записываются и курсор сдвигается одной транзакцией — после сбоя порция
     * повторяется целиком, без пропусков и повторов. Действующие — вставка или обновление по
     * регистрационному номеру (снова действующая — дата прекращения снимается); прекращённые —
     * отметка у уже известных.
     *
     * @param country   страна
     * @param registry  реестр
     * @param cursor    курсор до порции
     * @param companies юрлица порции
     * @param offset    обработано записей текущего файла после порции
     * @return курсор после порции
     * @throws OptimisticLockingFailureException курсор уже сдвинут другим заданием — порция откатывается
     */
    @Transactional
    public IntakeCursor applyBatch(String country, String registry, IntakeCursor cursor,
            List<RegistryCompany> companies, long offset) {
        List<RegistryCompany> active = companies.stream().filter(company -> company.terminatedOn() == null).toList();
        List<RegistryCompany> terminated = companies.stream().filter(company -> company.terminatedOn() != null)
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO company (country, registration_number, name, legal_form, municipality, registry, agency)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (country, registration_number) DO UPDATE
                SET name = EXCLUDED.name, legal_form = EXCLUDED.legal_form, municipality = EXCLUDED.municipality,
                    agency = EXCLUDED.agency, terminated_on = NULL, updated_at = now()
                """, active, active.size(), (statement, company) -> {
                statement.setString(1, country);
                statement.setString(2, company.registrationNumber());
                statement.setString(3, company.name());
                statement.setString(4, company.details().legalForm());
                statement.setString(5, company.details().municipality());
                statement.setString(6, registry);
                statement.setBoolean(7, company.details().agency());
            });
        jdbcTemplate.batchUpdate("""
                UPDATE company SET terminated_on = ?, updated_at = now()
                WHERE country = ? AND registration_number = ? AND terminated_on IS NULL
                """, terminated, terminated.size(), (statement, company) -> {
                statement.setObject(1, company.terminatedOn());
                statement.setString(2, country);
                statement.setString(3, company.registrationNumber());
            });
        return moveTo(country, cursor, cursor.withOffset(offset));
    }
}
