package com.roleorienta.api.saved;

import com.roleorienta.core.domain.JobPosting;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Публикация — цель маркера (§86). Узкий репозиторий пакета {@code saved}: раньше сервис маркеров
 * брал публикацию из {@code posting.PostingReadRepository}, а лента ({@code posting}) читает маркеры
 * — пакеты зависели друг от друга по кругу (правило ArchUnit «нет циклов пакетов»). Второй
 * репозиторий той же сущности допустим: имя бина Spring Data — по имени интерфейса.
 */
public interface MarkedPostingRepository extends Repository<JobPosting, Long> {

    /**
     * Публикация по id — проверка существования и цель связи маркера.
     *
     * @param id id публикации
     * @return публикация или пустое значение, если её нет
     */
    Optional<JobPosting> findById(Long id);
}
