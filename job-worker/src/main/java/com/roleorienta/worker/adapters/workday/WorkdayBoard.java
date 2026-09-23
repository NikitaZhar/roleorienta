package com.roleorienta.worker.adapters.workday;

/**
 * Найденная доска вакансий Workday: всё, что нужно {@link WorkdayAdapter}, чтобы читать
 * ленту (§5, ADR-17). Результат {@link WorkdaySiteResolver}.
 *
 * @param tenant  тенант — левая метка хоста {@code <tenant>.wd<N>.myworkdayjobs.com}
 *                (технический идентификатор, <b>не</b> имя работодателя — A2/A11)
 * @param site    сайт карьеры тенанта — сегмент пути cxs ({@code /wday/cxs/<tenant>/<site>/jobs})
 * @param baseUrl origin тенанта без завершающего слэша ({@code https://<tenant>.wd<N>.myworkdayjobs.com})
 */
public record WorkdayBoard(String tenant, String site, String baseUrl) {

    /**
     * Слаг кандидата и {@code Source.external_ref} в формате, который ждёт
     * {@link WorkdayAdapter}: {@code "<tenant>/<site>"}.
     *
     * @return {@code tenant + "/" + site}
     */
    public String slug() {
        return tenant + "/" + site;
    }
}
