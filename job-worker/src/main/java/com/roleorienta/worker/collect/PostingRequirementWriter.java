package com.roleorienta.worker.collect;

import com.roleorienta.core.domain.JobPosting;
import com.roleorienta.core.domain.PostingLanguage;
import com.roleorienta.core.domain.PostingSkill;
import com.roleorienta.worker.extract.ExtractedLanguage;
import com.roleorienta.worker.extract.ExtractedSkill;
import com.roleorienta.worker.extract.LanguageExtractor;
import com.roleorienta.worker.extract.SkillExtractor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Извлечение и запись структурированных требований публикации из текста описания:
 * языковых (§6, A07) и навыков/технологий (§6, A08).
 *
 * <p>Вынесено из {@link PostingEnricher}: добавление навыков довело бы конструктор
 * обогатителя до 6 зависимостей и нарушило правило «не более 5 параметров» (контракт
 * §3.10) — так же, как языки в §20.4 потребовали вынести сам {@code PostingEnricher}.
 * Оба извлечения работают от одного входа (текст описания) и пишут производные строки,
 * поэтому объединены в один компонент; языки и навыки хранятся в разных таблицах
 * (у языка своя модель A07), но их запись однотипна — полная замена «удалить + вставить».
 * Полная замена идемпотентна относительно содержимого текущего описания; ревизии по
 * языкам/навыкам в этот срез не вводятся.</p>
 */
@Component
public class PostingRequirementWriter {

    private final LanguageExtractor languageExtractor;
    private final PostingLanguageRepository postingLanguageRepository;
    private final SkillExtractor skillExtractor;
    private final PostingSkillRepository postingSkillRepository;

    /**
     * @param languageExtractor         извлечение языков
     * @param postingLanguageRepository языковые требования публикации
     * @param skillExtractor            извлечение навыков
     * @param postingSkillRepository    требования-навыки публикации
     */
    public PostingRequirementWriter(
            LanguageExtractor languageExtractor,
            PostingLanguageRepository postingLanguageRepository,
            SkillExtractor skillExtractor,
            PostingSkillRepository postingSkillRepository) {
        this.languageExtractor = languageExtractor;
        this.postingLanguageRepository = postingLanguageRepository;
        this.skillExtractor = skillExtractor;
        this.postingSkillRepository = postingSkillRepository;
    }

    /**
     * Извлекает и переписывает языковые и навыковые требования публикации.
     *
     * @param posting     публикация (управляемая сущность с присвоенным id)
     * @param description текст описания или {@code null}
     * @return число записанных языков и навыков (для логирования обогащения)
     */
    public RequirementCounts write(JobPosting posting, String description) {
        List<ExtractedLanguage> languages = languageExtractor.extract(description);
        replaceLanguages(posting, languages);
        List<ExtractedSkill> skills = skillExtractor.extract(description);
        replaceSkills(posting, skills);
        return new RequirementCounts(languages.size(), skills.size());
    }

    /** Переписывает языковые требования публикации (удалить прежние, вставить текущие). */
    private void replaceLanguages(JobPosting posting, List<ExtractedLanguage> languages) {
        postingLanguageRepository.deleteByJobPosting_Id(posting.getId());
        for (ExtractedLanguage language : languages) {
            PostingLanguage row = new PostingLanguage();
            row.setJobPosting(posting);
            row.setLanguageCode(language.languageCode());
            row.setMentioned(language.mentioned());
            row.setModality(language.modality());
            row.setSourceFragment(language.fragment());
            row.setExtractionVersion(LanguageExtractor.VERSION);
            postingLanguageRepository.save(row);
        }
    }

    /** Переписывает требования-навыки публикации (удалить прежние, вставить текущие). */
    private void replaceSkills(JobPosting posting, List<ExtractedSkill> skills) {
        postingSkillRepository.deleteByJobPosting_Id(posting.getId());
        for (ExtractedSkill skill : skills) {
            PostingSkill row = new PostingSkill();
            row.setJobPosting(posting);
            row.setSkill(skill.skill());
            row.setModality(skill.modality());
            row.setSourceFragment(skill.fragment());
            row.setExtractionVersion(SkillExtractor.VERSION);
            postingSkillRepository.save(row);
        }
    }

    /**
     * Счётчики записанных требований — для сводного лога обогащения.
     *
     * @param languages число языковых строк
     * @param skills    число строк-навыков
     */
    public record RequirementCounts(int languages, int skills) {
    }
}
