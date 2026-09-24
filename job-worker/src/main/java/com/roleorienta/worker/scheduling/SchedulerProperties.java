package com.roleorienta.worker.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки планировщика источников ({@link SourceScheduler}). Запись вместо {@code @Value} в
 * конструкторе — чтобы у него было не больше 5 параметров (контракт §3.10, §78).
 *
 * @param windowMs размер окна расписания, мс: в пределах одного окна источник планируется один раз
 */
@ConfigurationProperties(prefix = "app.scheduler")
public record SchedulerProperties(@DefaultValue("900000") long windowMs) {
}
