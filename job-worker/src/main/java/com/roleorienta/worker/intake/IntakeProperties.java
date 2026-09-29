package com.roleorienta.worker.intake;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки приёма компаний из реестра ({@code app.intake.*}).
 *
 * @param batchSize      записей реестра в одной транзакции (компании и сдвиг курсора вместе)
 * @param recordsPerTask записей за одно задание; дальше — следующее задание с того же места, чтобы
 *                       задание укладывалось в аренду. Продолжение открывает файл заново и
 *                       пропускает прочитанное, поэтому значение — порядка записей одного файла
 * @param rpoEndpoint    адрес S3-совместимого хранилища выгрузок RPO
 * @param rpoBucket      корзина выгрузок RPO
 * @param retention      сколько хранятся выгрузки RPO; ежедневной старше — не будет, цепочка
 *                       прервана, приём начинается с новой полной выгрузки
 */
@ConfigurationProperties("app.intake")
public record IntakeProperties(
        @DefaultValue("1000") int batchSize,
        @DefaultValue("200000") int recordsPerTask,
        @DefaultValue("https://frkqbrydxwdp.compat.objectstorage.eu-frankfurt-1.oraclecloud.com") String rpoEndpoint,
        @DefaultValue("susr-rpo") String rpoBucket,
        @DefaultValue("45d") Duration retention) {
}
