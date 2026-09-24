package com.roleorienta.api.posting;

import com.roleorienta.api.saved.SavedState;
import com.roleorienta.core.domain.CoverageState;
import com.roleorienta.core.domain.LanguageMention;
import com.roleorienta.core.domain.LanguageModality;
import com.roleorienta.core.domain.RequirementModality;
import com.roleorienta.core.domain.SalaryBasis;
import com.roleorienta.core.domain.SalaryPeriod;
import com.roleorienta.core.domain.SeniorityLevel;
import com.roleorienta.core.domain.SkillStance;
import com.roleorienta.core.domain.WorkModality;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO выдачи публикаций (§7): только данные, без логики (контракт §3.3). Перечисления
 * сериализуются именами (напр. {@code "REMOTE"}, {@code "NEGATED"}).
 *
 * <p><b>Поля сгруппированы по смыслу (§77).</b> Строка ленты и карточка собираются из одних и
 * тех же частей — {@link Head} (что за публикация) и {@link Facts} (где, сколько платят, какой
 * опыт, когда); у каждой записи не больше 5 полей (контракт §3.10). JSON повторяет группировку:
 * {@code items[0].head.title}, {@code items[0].facts.salary.min}, {@code items[0].viewer.state},
 * {@code items[0].coverage.state} (A5, §81).</p>
 */
public final class PostingDtos {

    private PostingDtos() {
    }

    /**
     * Что за публикация.
     *
     * @param id         идентификатор публикации
     * @param externalId идентификатор в источнике
     * @param title      заголовок как в источнике
     * @param url        ссылка на публикацию
     */
    public record Head(Long id, String externalId, String title, String url) {
    }

    /**
     * Где работа.
     *
     * @param city        город или {@code null}
     * @param country     страна или {@code null}
     * @param workModality формат работы ({@code UNKNOWN}, если источник не сообщил)
     * @param raw         локация как в источнике
     * @param additional  дополнительные локации через «; » или {@code null}
     */
    public record Location(String city, String country, WorkModality workModality, String raw, String additional) {
    }

    /**
     * Зарплата (A09): суммы, валюта, период и база раздельно; всё {@code null} — не указана.
     *
     * @param min      нижняя граница или {@code null}
     * @param max      верхняя граница или {@code null} («от X»)
     * @param currency код валюты
     * @param period   период ({@code UNKNOWN}, если в источнике не указан)
     * @param basis    gross/net ({@code UNKNOWN}, если не указано)
     */
    public record Salary(BigDecimal min, BigDecimal max, String currency, SalaryPeriod period, SalaryBasis basis) {
    }

    /**
     * Требуемый опыт (A08): уровень и число лет раздельно.
     *
     * @param seniority уровень ({@code UNKNOWN}, если в заголовке нет)
     * @param yearsMin  минимум лет или {@code null}
     */
    public record Experience(SeniorityLevel seniority, Integer yearsMin) {
    }

    /**
     * Даты публикации: по источнику и по нашему сбору.
     *
     * @param postedOn        дата публикации по источнику или {@code null}
     * @param firstSeenAt     впервые увидели
     * @param lastSeenAt      последний раз видели в ленте
     * @param detailFetchedAt когда читали деталь или {@code null}
     */
    public record Timeline(LocalDate postedOn, Instant firstSeenAt, Instant lastSeenAt, Instant detailFetchedAt) {
    }

    /**
     * Нормализованные сведения о публикации — общие для строки ленты и карточки.
     *
     * @param location   где
     * @param salary     сколько
     * @param experience какой опыт
     * @param timeline   когда
     */
    public record Facts(Location location, Salary salary, Experience experience, Timeline timeline) {
    }

    /**
     * Отношение вошедшего пользователя к публикации (§31). Для анонимного запроса —
     * {@code state = null}, {@code seen = false}.
     *
     * @param state сохранена/скрыта/… или {@code null}
     * @param seen  отмечена просмотренной
     */
    public record Viewer(SavedState state, boolean seen) {
    }

    /**
     * Покрытие публикации площадками (A5, ADR-15): результат сравнения, а не факт.
     * {@code UNKNOWN} — не проверено; отрицательный результат читается ограниченно: «не найдена
     * на {@code checkedPlatforms} при проверке {@code checkedAt}».
     *
     * @param state            состояние покрытия
     * @param checkedPlatforms проверенные площадки или {@code null}
     * @param reason           причина состояния (для {@code UNKNOWN} — почему не проверено)
     * @param checkedAt        когда выполнено сравнение или {@code null}
     */
    public record Coverage(CoverageState state, String checkedPlatforms, String reason, Instant checkedAt) {

        /** Публикация без оценки: сравнение с площадками не выполнялось. */
        public static final Coverage NOT_CHECKED =
                new Coverage(CoverageState.UNKNOWN, null, "сравнение с площадками не выполнялось", null);
    }

    /**
     * Строка ленты.
     *
     * @param head     что за публикация
     * @param facts    нормализованные сведения
     * @param viewer   отношение вошедшего пользователя
     * @param coverage покрытие площадками (A5)
     */
    public record Summary(Head head, Facts facts, Viewer viewer, Coverage coverage) {
    }

    /** Языковое требование в карточке (A07): факт упоминания и обязательность раздельно. */
    public record Language(String languageCode, LanguageMention mentioned, LanguageModality modality) {
    }

    /** Требование-навык в карточке (A08): отношение и обязательность раздельно. */
    public record Skill(String skill, SkillStance stance, RequirementModality modality) {
    }

    /**
     * Требования публикации, извлечённые из описания.
     *
     * @param languages языки (по коду)
     * @param skills    навыки (по названию)
     */
    public record Requirements(List<Language> languages, List<Skill> skills) {
    }

    /**
     * Карточка публикации — строка ленты плюс описание и требования.
     *
     * @param head         что за публикация
     * @param facts        нормализованные сведения
     * @param description  текст описания или {@code null}
     * @param requirements языки и навыки
     * @param coverage     покрытие площадками (A5)
     */
    public record Card(Head head, Facts facts, String description, Requirements requirements, Coverage coverage) {
    }

    /** Страница ленты: элементы и курсор следующей страницы ({@code null} — конец). */
    public record Page(List<Summary> items, Long nextCursor) {
    }
}
