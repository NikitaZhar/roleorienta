package com.roleorienta.api.condition;

import com.roleorienta.api.condition.ConditionDtos.Direction;
import com.roleorienta.api.condition.ConditionDtos.PositionOption;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Выбор позиции в условиях (§204): подсказка при вводе и «Полный каталог»; запрос без результата запоминается для
 * администратора (§205).
 */
@Service
public class OccupationService {

    private final OccupationRepository occupations;

    /**
     * @param occupations позиции словаря и группы ISCO-08
     */
    public OccupationService(OccupationRepository occupations) {
        this.occupations = occupations;
    }

    /**
     * Подсказка; ничего не нашлось — строка запоминается, если запрос от вошедшего пользователя: подсказка открыта и
     * без входа, а список администратора не должен накручиваться анонимными запросами (аудит §211). Запоминание —
     * одной транзакцией (замена начала слова полной строкой).
     *
     * @param query    строка
     * @param language язык названий групп
     * @param signedIn запрос вошедшего пользователя
     * @return варианты
     */
    @Transactional
    public List<PositionOption> suggestions(String query, String language, boolean signedIn) {
        List<PositionOption> found = occupations.suggestions(query, language);
        if (found.isEmpty() && signedIn) {
            occupations.recordMiss(query);
        }
        return found;
    }

    /**
     * @param language язык названий
     * @return «Полный каталог»: направления → группы → позиции словаря
     */
    @Transactional(readOnly = true)
    public List<Direction> catalog(String language) {
        return occupations.catalog(language);
    }
}
