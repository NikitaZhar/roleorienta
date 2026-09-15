package com.roleorienta.worker.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Обработчик-заглушка на текущем этапе: подтверждает получение задания, но не
 * выполняет доменной логики (реальные обработчики — сбор, обнаружение,
 * агрегация — добавляются в следующих инкрементах). Существует, чтобы магистраль
 * доставки была работоспособна и проверяема end-to-end уже сейчас.
 */
@Component
public class NoOpJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(NoOpJobHandler.class);

    @Override
    public void handle(JobMessage message) {
        log.info("Получено задание eventType={} key={} (обработчик-заглушка, доменной логики пока нет)",
                message.eventType(), message.idempotencyKey());
    }
}
