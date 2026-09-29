package com.roleorienta.worker.intake;

import java.time.LocalDate;

/**
 * Место обработки реестра страны (таблица {@code intake_cursor}).
 *
 * @param exportDate   дата полной выгрузки
 * @param fileIndex    номер читаемого файла полной выгрузки (с 0)
 * @param recordOffset сколько записей текущего файла уже обработано
 * @param dailyDate    последняя применённая ежедневная выгрузка; {@code null} — читается полная
 * @param version      версия курсора; растёт с каждым сдвигом
 */
public record IntakeCursor(LocalDate exportDate, int fileIndex, long recordOffset, LocalDate dailyDate,
        long version) {

    /**
     * @param offset обработано записей текущего файла
     * @return тот же курсор с новым местом в файле
     */
    public IntakeCursor withOffset(long offset) {
        return new IntakeCursor(exportDate, fileIndex, offset, dailyDate, version);
    }
}
