package com.roleorienta.worker.geo;

import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Задание {@code GEO_IMPORT}: справочник городов GeoNames (технический документ §3 «Справочники»;
 * лицензия CC BY 4.0, https://www.geonames.org/export/) — файл {@code cities15000.zip}: города с
 * населением от 15 000, строки через табуляцию (название, латинское название, другие названия, …,
 * страна, …, население). Берутся название и латинское название; для Словакии — и другие названия
 * (Pozsony, Kaschau …). Название нормализуется так же, как текст вакансий (регистр, диакритика).
 * Справочник заменяется целиком. Официальный набор открытых данных — свой клиент (JDK
 * {@link HttpClient}) с таймаутом; сбой — повтор задания.
 */
@Component
public class GeoImportHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "GEO_IMPORT";

    private static final String ENTRY = "cities15000.txt";
    private static final String ALTERNATES_COUNTRY = "SK";
    private static final int NAME = 1;
    private static final int ASCII_NAME = 2;
    private static final int ALTERNATE_NAMES = 3;
    private static final int COUNTRY = 8;
    private static final int POPULATION = 14;
    private static final int MAX_NAME = 200;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** Разделители — пробел, как при нормализации мест вакансий ({@code PositionDictionary.normalize}). */
    private static final Pattern SEPARATORS = Pattern.compile("[\\p{Pd}/|,()\\[\\]:;\\s]+");
    private static final Logger LOG = LoggerFactory.getLogger(GeoImportHandler.class);

    private final GeoRepository repository;
    private final String url;

    /**
     * @param repository справочник городов
     * @param url        адрес архива GeoNames ({@code app.geo.url})
     */
    public GeoImportHandler(GeoRepository repository, @Value("${app.geo.url}") String url) {
        this.repository = repository;
        this.url = url;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TaskOutcome handle(TaskRecord task) {
        try {
            Map<String, GeoCity> cities = download();
            repository.replace(cities.values());
            LOG.info("GeoNames: {} city names imported", cities.size());
            return new TaskOutcome.Done();
        } catch (IOException exception) {
            return new TaskOutcome.Retry("GeoNames not read: " + exception.getMessage(), Duration.ZERO);
        }
    }

    private Map<String, GeoCity> download() throws IOException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", interrupted);
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode());
        }
        try (ZipInputStream zip = new ZipInputStream(response.body())) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (ENTRY.equals(entry.getName())) {
                    return parse(new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8)));
                }
            }
        }
        throw new IOException(ENTRY + " not found in " + url);
    }

    /**
     * @return города по ключу «название|страна»; при повторе — самый населённый
     */
    private static Map<String, GeoCity> parse(BufferedReader lines) throws IOException {
        Map<String, GeoCity> cities = new HashMap<>();
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            String[] fields = line.split("\t", -1);
            if (fields.length <= POPULATION) {
                continue;
            }
            String country = fields[COUNTRY];
            long population = fields[POPULATION].isEmpty() ? 0 : Long.parseLong(fields[POPULATION]);
            add(cities, fields[NAME], country, population);
            add(cities, fields[ASCII_NAME], country, population);
            if (ALTERNATES_COUNTRY.equals(country)) {
                for (String alternate : fields[ALTERNATE_NAMES].split(",")) {
                    add(cities, alternate, country, population);
                }
            }
        }
        return cities;
    }

    private static void add(Map<String, GeoCity> cities, String name, String country, long population) {
        String decomposed = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        String normalized = SEPARATORS.matcher(MARKS.matcher(decomposed).replaceAll("")).replaceAll(" ").strip();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME || country.length() != 2) {
            return;
        }
        cities.merge(normalized + "|" + country, new GeoCity(normalized, country, population),
                (known, candidate) -> known.population() >= candidate.population() ? known : candidate);
    }
}
