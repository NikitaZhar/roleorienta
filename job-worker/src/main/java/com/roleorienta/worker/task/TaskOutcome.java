package com.roleorienta.worker.task;

import java.time.Duration;

/**
 * Результат выполнения задания обработчиком.
 *
 * <p>{@code sealed}: других вариантов нет, поэтому {@code switch} по результату проверяется
 * компилятором на полноту. https://docs.oracle.com/en/java/javase/21/language/sealed-classes-and-interfaces.html</p>
 */
public sealed interface TaskOutcome {

    /**
     * Задание выполнено.
     */
    record Done() implements TaskOutcome {
    }

    /**
     * Временный отказ (таймаут, 5xx, 429, неполный ответ): повторить позже.
     *
     * @param reason       причина для журнала задания
     * @param minimumDelay не повторять раньше этого срока (например, {@code Retry-After}
     *                     источника); {@link Duration#ZERO} — ограничения нет
     */
    record Retry(String reason, Duration minimumDelay) implements TaskOutcome {
    }

    /**
     * Окончательная неудача: повтор не поможет.
     *
     * @param reason причина для журнала задания
     */
    record Failed(String reason) implements TaskOutcome {
    }
}
