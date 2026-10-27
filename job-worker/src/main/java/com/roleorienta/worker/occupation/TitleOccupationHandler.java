package com.roleorienta.worker.occupation;

import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Задание {@code TITLE_OCCUPATIONS} (§215): раз в сутки переучить профессию по названию на публикациях с кодом портала
 * и перераспознать публикации без кода ({@link TitleOccupationRepository#relearn}). Выдача, результаты и сводка видят
 * распознанную группу сразу — через представление {@code search_condition_match}.
 */
@Component
public class TitleOccupationHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "TITLE_OCCUPATIONS";

    private static final Logger LOG = LoggerFactory.getLogger(TitleOccupationHandler.class);

    private final TitleOccupationRepository repository;

    /**
     * @param repository обучение и распознавание
     */
    public TitleOccupationHandler(TitleOccupationRepository repository) {
        this.repository = repository;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        TitleOccupationRepository.Result result = repository.relearn();
        LOG.info("Title occupations: {} heads learned, {} postings changed", result.learned(), result.changed());
        return new TaskOutcome.Done();
    }
}
