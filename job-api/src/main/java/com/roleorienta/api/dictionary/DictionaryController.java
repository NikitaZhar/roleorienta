package com.roleorienta.api.dictionary;

import com.roleorienta.api.dictionary.DictionaryDtos.ChosenWithoutVacancies;
import com.roleorienta.api.dictionary.DictionaryDtos.DictionaryView;
import com.roleorienta.api.dictionary.DictionaryDtos.NewPosition;
import com.roleorienta.api.dictionary.DictionaryDtos.PositionChange;
import com.roleorienta.api.dictionary.DictionaryDtos.SearchMiss;
import com.roleorienta.api.dictionary.DictionaryDtos.Settings;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Словарь позиций для администратора (технический документ §8; стенограмма §160). Доступ к
 * {@code /api/v1/admin/**} — только роль {@code ADMIN} ({@code SecurityConfig}). Каждое изменение возвращает словарь
 * целиком с новым номером версии.
 */
@RestController
public class DictionaryController {

    private final DictionaryService dictionary;

    /**
     * @param dictionary словарь позиций
     */
    public DictionaryController(DictionaryService dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * @return номер версии, общие настройки и все позиции (и выключенные) с названиями по видам
     */
    @GetMapping("/api/v1/admin/dictionary")
    public DictionaryView dictionary() {
        return dictionary.dictionary();
    }

    /**
     * @param settings сколько раз признак должен встретиться в тексте и признаки специализаций вне словаря
     * @return словарь; {@code 400} — неверные поля
     */
    @PutMapping("/api/v1/admin/dictionary")
    public DictionaryView settings(@Valid @RequestBody Settings settings) {
        return dictionary.change(settings);
    }

    /**
     * @param position код, название, названия по видам (хотя бы одно эквивалентное название)
     * @return словарь, {@code 201}; {@code 400} — неверные поля; {@code 409} — код уже есть
     */
    @PostMapping("/api/v1/admin/positions")
    @ResponseStatus(HttpStatus.CREATED)
    public DictionaryView add(@Valid @RequestBody NewPosition position) {
        return dictionary.add(position).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Position " + position.code() + " exists"));
    }

    /**
     * @param id     позиция
     * @param change название, включена ли, названия по видам
     * @return словарь; {@code 400} — неверные поля; {@code 404} — позиции нет
     */
    @PutMapping("/api/v1/admin/positions/{id}")
    public DictionaryView change(@PathVariable long id, @Valid @RequestBody PositionChange change) {
        return dictionary.change(id, change).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Position not found"));
    }

    /**
     * @return что искали в подсказке позиции и не нашли (§205): строка, сколько раз, когда последний раз; чаще —
     *         первыми, не больше 100
     */
    @GetMapping("/api/v1/admin/position-misses")
    public List<SearchMiss> searchMisses() {
        return dictionary.searchMisses();
    }

    /**
     * @param query запрос
     * @return {@code 204}; {@code 404} — такого нет
     */
    @DeleteMapping("/api/v1/admin/position-misses")
    public ResponseEntity<Void> forgetSearchMiss(@RequestParam String query) {
        if (!dictionary.forgetSearchMiss(query)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Search miss not found");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * @return позиции и группы, выбранные в действующих условиях, без единой открытой вакансии (§214), чаще выбранные
     *         — первыми
     */
    @GetMapping("/api/v1/admin/positions/without-vacancies")
    public List<ChosenWithoutVacancies> chosenWithoutVacancies() {
        return dictionary.chosenWithoutVacancies();
    }
}
