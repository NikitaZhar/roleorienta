package com.roleorienta.worker.intake;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Запись выгрузки RPO → юрлицо. Структура записи — как ответ {@code /entity/{id}} API RPO: списки
 * значений с периодами действия ({@code identifiers}, {@code fullNames}, {@code legalForms},
 * {@code addresses}: {@code value}, {@code validFrom}, {@code validTo}), {@code termination};
 * основной вид деятельности — {@code statisticalCodes.mainActivity.code} (SK NACE, 4 цифры): код 78xx —
 * кадровое агентство (образец — {@code docs/samples/rpo-daily.json}).
 * Текущее значение — без {@code validTo}; если такого нет — последнее. https://rpo.minv.sk/rpo-api-doc.html
 *
 * <p>Не берутся: запись без IČO или названия; предприниматель-физлицо (правовая форма 101–110
 * числового кода или «fyzická osoba» / «roľník» в названии формы — решение владельца, §24). Органы
 * власти и города — юрлица, берутся.</p>
 */
final class RpoRecordParser {

    private static final Set<String> NATURAL_PERSON_FORM_CODES = Set.of(
            "101", "102", "103", "104", "105", "106", "107", "108", "109", "110");
    private static final List<String> NATURAL_PERSON_FORM_MARKERS = List.of("fyzická osoba", "roľník");
    /** SK NACE 78 — агентства занятости: подбор, временное трудоустройство, передача персонала. */
    private static final String AGENCY_ACTIVITY = "78";
    private static final int MAX_NAME = 500;
    private static final int MAX_SHORT_TEXT = 200;

    private RpoRecordParser() {
    }

    /**
     * @param record запись выгрузки
     * @return юрлицо; пусто — запись не берётся
     */
    static Optional<RegistryCompany> parse(JsonNode record) {
        String number = text(current(record.path("identifiers")).path("value"));
        String name = text(current(record.path("fullNames")).path("value"));
        if (number == null || name == null) {
            return Optional.empty();
        }
        JsonNode form = current(record.path("legalForms")).path("value");
        String formName = text(form.path("value"));
        if (NATURAL_PERSON_FORM_CODES.contains(text(form.path("code"))) || isNaturalPersonForm(formName)) {
            return Optional.empty();
        }
        boolean agency = record.path("statisticalCodes").path("mainActivity").path("code").asText()
                .startsWith(AGENCY_ACTIVITY);
        return Optional.of(new RegistryCompany(number, limit(name, MAX_NAME), new RegistryCompany.Details(
                limit(formName, MAX_SHORT_TEXT),
                limit(text(current(record.path("addresses")).path("municipality").path("value")), MAX_SHORT_TEXT),
                agency), date(record.path("termination"))));
    }

    private static boolean isNaturalPersonForm(String formName) {
        String lower = formName == null ? "" : formName.toLowerCase(Locale.ROOT);
        return NATURAL_PERSON_FORM_MARKERS.stream().anyMatch(lower::contains);
    }

    /**
     * Текущее значение списка с периодами: без {@code validTo}, иначе последнее; пустой список —
     * отсутствующий узел.
     */
    private static JsonNode current(JsonNode entries) {
        JsonNode last = entries.path(entries.size() - 1);
        for (JsonNode entry : entries) {
            if (entry.path("validTo").isMissingNode() || entry.path("validTo").isNull()) {
                return entry;
            }
        }
        return last;
    }

    private static String text(JsonNode node) {
        return node.isValueNode() && !node.asText().isBlank() ? node.asText().strip() : null;
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static LocalDate date(JsonNode node) {
        String value = text(node);
        try {
            return value == null ? null : LocalDate.parse(value.substring(0, Math.min(value.length(), 10)));
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }
}
