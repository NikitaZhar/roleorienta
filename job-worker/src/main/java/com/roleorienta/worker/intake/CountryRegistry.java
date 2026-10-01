package com.roleorienta.worker.intake;

import java.io.IOException;

/**
 * Реестр юрлиц одной страны — основной вход общего сбора (технический документ §5.1: «каждая страна —
 * адаптер реестра»; бизнес-описание §4.1). Место обработки хранит сам реестр (у RPO — курсор
 * {@code intake_cursor}): после остановки следующая партия страны продолжается с него.
 */
public interface CountryRegistry {

    /**
     * @return страна реестра (ISO 3166-1 alpha-2)
     */
    String country();

    /**
     * Читает одну партию с места обработки страны и сохраняет новое место.
     *
     * @return {@code true} — записи ещё есть; {@code false} — читать пока нечего
     * @throws IOException реестр временно не читается — партия повторяется
     */
    boolean intakeBatch() throws IOException;
}
