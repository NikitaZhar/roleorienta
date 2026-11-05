package com.roleorienta.worker.stateportal;

import com.roleorienta.worker.adapter.stateportal.ProfesiaPosition;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Запись позиций profesia.sk объявления (§267) — общая для объявлений общего списка портала
 * ({@link PortalListRepository}) и добранных с profesia.sk ({@link ProfesiaOfferRepository}): позиция каталога
 * ({@code profesia_position}) добавляется, если её ещё нет (группу ISCO-08 ей ставит распознавание по названию позиции,
 * владелец может исправить); позиции объявления ({@code profesia_ad_position}) — по номеру объявления, без повторов.
 * Вызывается внутри транзакции вызывающего.
 */
final class ProfesiaPositionRecords {

    private ProfesiaPositionRecords() {
    }

    /**
     * @param jdbcTemplate доступ к БД
     * @param num          номер объявления profesia.sk
     * @param positions    позиции объявления
     */
    static void save(JdbcTemplate jdbcTemplate, long num, List<ProfesiaPosition> positions) {
        for (ProfesiaPosition position : positions) {
            jdbcTemplate.update("INSERT INTO profesia_position (slug, name) VALUES (?, ?) ON CONFLICT (slug) DO NOTHING",
                    position.slug(), position.name());
            jdbcTemplate.update("INSERT INTO profesia_ad_position (num, slug) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    num, position.slug());
        }
    }

    /**
     * @param externalId внешний id публикации {@code profesia:<номер>}
     * @return номер объявления; не объявление profesia.sk — {@code -1}
     */
    static long number(String externalId) {
        String digits = externalId.startsWith("profesia:") ? externalId.substring("profesia:".length()) : "";
        return digits.matches("\\d{1,18}") ? Long.parseLong(digits) : -1;
    }
}
