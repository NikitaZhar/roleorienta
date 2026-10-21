package com.roleorienta.api.dictionary;

import com.roleorienta.api.account.UserAccountRepository;
import com.roleorienta.api.condition.InvalidFieldException;
import com.roleorienta.api.dictionary.SuggestionDtos.AdminSuggestionItem;
import com.roleorienta.api.dictionary.SuggestionDtos.DecisionRequest;
import com.roleorienta.api.dictionary.SuggestionDtos.State;
import com.roleorienta.api.dictionary.SuggestionDtos.SuggestionItem;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Предложения позиций (бизнес-описание §3; решения владельца 2026-10-21, стенограмма §161): пользователь предлагает
 * позицию, которой нет в словаре, — не больше {@value #DAILY_LIMIT} за сутки; администратор добавляет её в словарь
 * (раздел «Position dictionary», затем решение «добавлена» с этой позицией) или отклоняет с необязательной причиной;
 * решение можно изменить. Пользователь видит только свои предложения.
 */
@Service
public class SuggestionService {

    /** Предложений от одного пользователя за последние сутки не больше. */
    static final int DAILY_LIMIT = 10;

    private final SuggestionRepository repository;
    private final UserAccountRepository accounts;

    /**
     * @param repository предложения
     * @param accounts   блокировка строки пользователя
     */
    public SuggestionService(SuggestionRepository repository, UserAccountRepository accounts) {
        this.repository = repository;
        this.accounts = accounts;
    }

    /**
     * Одна транзакция: строка пользователя блокируется, считаются предложения за сутки, предложение записывается.
     *
     * @param userId пользователь
     * @param text   название позиции и примеры названий вакансий
     * @return предложение на рассмотрении; пусто — лимит за сутки исчерпан
     */
    @Transactional
    public Optional<SuggestionItem> suggest(long userId, String text) {
        accounts.lock(userId);
        if (repository.countLastDay(userId) >= DAILY_LIMIT) {
            return Optional.empty();
        }
        return Optional.of(repository.insert(userId, text.strip()));
    }

    /**
     * @param userId пользователь
     * @return его предложения, новые сверху
     */
    public List<SuggestionItem> own(long userId) {
        return repository.byUser(userId);
    }

    /**
     * @return все предложения для администратора: на рассмотрении — первыми
     */
    public List<AdminSuggestionItem> all() {
        return repository.all();
    }

    /**
     * Решение администратора: «добавлена» — с позицией словаря (и выключенной: решение о ней принято), «отклонена» —
     * с необязательной причиной (пустая — без причины).
     *
     * @param id       предложение
     * @param decision решение
     * @return {@code false} — предложения нет
     * @throws InvalidFieldException «на рассмотрении» вместо решения ({@code /decision}); «добавлена» без позиции или с
     *                               неизвестной позицией ({@code /position})
     */
    @Transactional
    public boolean decide(long id, DecisionRequest decision) {
        if (decision.decision() == State.PENDING) {
            throw new InvalidFieldException("/decision", "Decision is ADDED or REJECTED");
        }
        if (decision.decision() == State.REJECTED) {
            String reason = decision.reason() == null || decision.reason().isBlank() ? null : decision.reason().strip();
            return repository.decide(id, State.REJECTED, null, reason);
        }
        String code = decision.position() == null ? "" : decision.position().strip();
        Long positionId = repository.positionId(code)
                .orElseThrow(() -> new InvalidFieldException("/position", "Unknown position: " + code));
        return repository.decide(id, State.ADDED, positionId, null);
    }
}
