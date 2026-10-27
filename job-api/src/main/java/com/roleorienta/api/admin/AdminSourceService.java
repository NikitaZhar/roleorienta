package com.roleorienta.api.admin;

import com.roleorienta.api.admin.AdminDtos.UnlinkedSource;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * «Sources without company» на /admin (§206): собственные кадровые страницы без компании и их закрытие.
 */
@Service
public class AdminSourceService {

    private final AdminSourceRepository sources;

    /**
     * @param sources источники без компании
     */
    public AdminSourceService(AdminSourceRepository sources) {
        this.sources = sources;
    }

    /**
     * @return незакрытые кадровые страницы без компании с открытыми публикациями
     */
    @Transactional(readOnly = true)
    public List<UnlinkedSource> unlinked() {
        return sources.unlinked();
    }

    /**
     * Закрывает источник одной транзакцией: источник, его открытые публикации и вакансии без других открытых
     * публикаций (как уборка V71).
     *
     * @param sourceId источник
     * @return {@code false} — нет незакрытого источника без компании
     */
    @Transactional
    public boolean close(long sourceId) {
        if (!sources.markClosed(sourceId)) {
            return false;
        }
        sources.closeVacancies(sourceId);
        sources.closePostings(sourceId);
        return true;
    }
}
