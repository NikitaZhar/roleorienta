package com.roleorienta.worker.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Названия работодателей, заданные вручную (§123): файл {@code source-employers.json} приложения — для источников
 * без компании, чья система найма названия не даёт (Workday Caterpillar и HPE: {@code hiringOrganization.name}
 * пустое). Файл ведётся как данные: новая запись — правка файла, код не меняется.
 *
 * <p>Названия записываются в {@code source.employer_name} при запуске воркера (файл входит в сборку, поэтому меняется
 * только вместе с перезапуском) и заменяют название от системы найма. Источника из файла ещё нет — запись
 * пропускается; она применится при следующем запуске. Недоступность БД при запуске не мешает запуску: названия
 * запишутся при следующем.</p>
 */
@Component
public class ManualEmployerNames {

    private static final String RESOURCE = "/source-employers.json";

    private static final Logger LOG = LoggerFactory.getLogger(ManualEmployerNames.class);

    private final SourceRepository sources;
    private final List<Entry> entries;

    /**
     * Загружает файл из ресурсов приложения. {@link Autowired} указывает Spring, каким конструктором создавать
     * компонент: конструкторов два (второй — для тестов), без указания Spring ищет конструктор без параметров.
     *
     * @param sources доступ к источникам
     */
    @Autowired
    public ManualEmployerNames(SourceRepository sources) {
        this(sources, readResource());
    }

    /**
     * @param sources доступ к источникам
     * @param file    содержимое в формате {@code source-employers.json}
     */
    ManualEmployerNames(SourceRepository sources, JsonNode file) {
        this.sources = sources;
        List<Entry> loaded = new ArrayList<>();
        for (JsonNode employer : file.path("employers")) {
            Entry entry = new Entry(employer.path("provider").asText(), employer.path("board").asText(),
                    employer.path("name").asText().strip());
            if (entry.provider().isBlank() || entry.board().isBlank() || entry.name().isBlank()) {
                throw new IllegalStateException("Manual employer name without provider, board or name: " + employer);
            }
            loaded.add(entry);
        }
        this.entries = List.copyOf(loaded);
    }

    /**
     * @return записи файла
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Записывает названия файла в источники. Вызывается Spring после готовности приложения
     * ({@link ApplicationReadyEvent}).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void apply() {
        try {
            int written = 0;
            for (Entry entry : entries) {
                written += sources.nameEmployerManually(entry.provider(), entry.board(), entry.name());
            }
            LOG.info("Manual employer names: {} in file, {} written", entries.size(), written);
        } catch (DataAccessException exception) {
            LOG.warn("Manual employer names not written, retried on next start: {}", exception.getMessage());
        }
    }

    private static JsonNode readResource() {
        try (InputStream input = ManualEmployerNames.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Manual employer names " + RESOURCE + " are missing");
            }
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("Manual employer names " + RESOURCE + " are not readable", exception);
        }
    }

    /**
     * Запись файла.
     *
     * @param provider провайдер источника
     * @param board    доска, как записана в {@code source.board}
     * @param name     название работодателя
     */
    public record Entry(String provider, String board, String name) {
    }
}
