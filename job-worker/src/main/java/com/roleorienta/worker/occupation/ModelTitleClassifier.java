package com.roleorienta.worker.occupation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Распознавание позиции локальной языковой моделью ({@link TitleClassifier}; стенограмма §262): модель с открытыми весами
 * в Ollama на том же компьютере, бесплатно. Пачка названий — один запрос чата Ollama ({@code /api/chat},
 * {@code stream: false}): модель получает каталог групп ISCO-08 уровня 4 (код и название) и нумерованные названия и
 * отвечает строками «номер — код» (или «-», если это не вакансия или профессию не определить). Каталог — одинаковое
 * начало каждого запроса: Ollama не пересчитывает уже обработанное начало подсказки, повторные пачки быстрее.
 * {@code temperature: 0} — ответ на одно и то же название не меняется от запроса к запросу.
 * https://github.com/ollama/ollama/blob/main/docs/api.md
 *
 * <p>Запрос — {@link HttpClient} JDK, а не {@code ExternalHttpClient} ядра: это не чтение чужого сайта (robots.txt,
 * пауза на хост и снимки к нему не относятся), а своя программа на {@code localhost}. Ответ не HTTP 200, Ollama не
 * запущена или не ответила в срок — {@link ClassifierUnavailable}: пачка спрашивается позже. Код, которого нет в
 * каталоге, и номер без названия отбрасываются — модель не может добавить группу или ответить за чужое название.</p>
 */
@Component
public class ModelTitleClassifier implements TitleClassifier {

    private static final int OK = 200;
    private static final Pattern ANSWER = Pattern.compile("(?m)^\\s*(\\d{1,4})\\s*[\\t:.)\\-]?\\s*(\\d{4}|-)\\s*$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSTRUCTIONS = """
            You classify job vacancy titles into ISCO-08 unit groups (4-digit codes).
            Titles come from job boards and employers' career pages, in any language (Slovak, Czech, English, German,
            Hungarian, Spanish, Italian ...). Classify by the occupation a person would actually do, ignoring
            seniority (Senior, Junior, Intern, Lead), technologies and products (Java, Azure, SAP, ServiceNow),
            required languages ("with French"), workplace, salary and gender marks ("m/ž", "muž/žena", "m/w/d").
            Answer only with one line per title: the title number, a tab, and the 4-digit code from the list below.
            Write "-" instead of the code when the text is not a job vacancy or the occupation cannot be determined.
            No other text.

            ISCO-08 unit groups (code, name):
            """;

    private final TitleClassificationProperties properties;
    private final HttpClient http;

    /**
     * @param properties модель, адрес Ollama, срок ответа, контекст
     */
    public ModelTitleClassifier(TitleClassificationProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.api().timeout()).build();
    }

    @Override
    public Map<String, String> classify(List<String> titles, List<Group> groups) {
        if (titles.isEmpty()) {
            return Map.of();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.api().endpoint()))
                .timeout(properties.api().timeout())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(titles, groups)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new ClassifierUnavailable("Model request failed: " + failure.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ClassifierUnavailable("Model request interrupted");
        }
        if (response.statusCode() != OK) {
            throw new ClassifierUnavailable("Model answered HTTP " + response.statusCode());
        }
        return parse(text(response.body()), titles, groups.stream().map(Group::code).collect(Collectors.toSet()));
    }

    /**
     * Тело запроса чата Ollama: каталог — системное сообщение, названия — нумерованными строками.
     */
    String body(List<String> titles, List<Group> groups) {
        StringBuilder catalog = new StringBuilder(INSTRUCTIONS);
        groups.forEach(group -> catalog.append(group.code()).append('\t').append(group.name()).append('\n'));
        StringBuilder numbered = new StringBuilder();
        for (int index = 0; index < titles.size(); index++) {
            numbered.append(index + 1).append('\t').append(titles.get(index).replace('\n', ' ')).append('\n');
        }
        ObjectNode root = JSON.createObjectNode().put("model", properties.model()).put("stream", false);
        root.putObject("options").put("temperature", 0).put("num_ctx", properties.api().contextTokens());
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", catalog.toString());
        messages.addObject().put("role", "user").put("content", numbered.toString());
        return root.toString();
    }

    /**
     * @param body ответ чата Ollama
     * @return текст ответа модели ({@code message.content}); ответ не разобран — пусто
     */
    static String text(String body) {
        try {
            JsonNode content = JSON.readTree(body).path("message").path("content");
            return content.isTextual() ? content.asText() : "";
        } catch (IOException malformed) {
            return "";
        }
    }

    /**
     * Строки «номер — код» → название — код; «-» — пустое значение (не распознано); номер вне пачки и код вне каталога
     * отбрасываются (названия без ответа не входят в результат — спросятся снова).
     */
    static Map<String, String> parse(String text, List<String> titles, Set<String> codes) {
        Map<String, String> result = new HashMap<>();
        Matcher line = ANSWER.matcher(text);
        while (line.find()) {
            int number = Integer.parseInt(line.group(1));
            String code = line.group(2);
            if (number >= 1 && number <= titles.size() && ("-".equals(code) || codes.contains(code))) {
                result.put(titles.get(number - 1), "-".equals(code) ? "" : code);
            }
        }
        return result;
    }
}
