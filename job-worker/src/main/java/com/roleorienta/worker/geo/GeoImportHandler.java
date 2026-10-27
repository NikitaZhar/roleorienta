package com.roleorienta.worker.geo;

import com.roleorienta.worker.task.TaskHandler;
import com.roleorienta.worker.task.TaskOutcome;
import com.roleorienta.worker.task.TaskRecord;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * (Pozsony, Kaschau …); для городов от {@value #ALTERNATES_MIN_POPULATION} жителей любой страны — другие названия не
 * короче {@value #MIN_ALTERNATE} букв (§210: Warszawa, Bangalore, Kyiv — места вакансий на языке страны или старым
 * названием; у малых городов другие названия дают ложные совпадения). Смена правил — {@link #RULES}: справочник
 * загружается заново ({@code GeoImportTick}). Название нормализуется так же, как текст вакансий (регистр, диакритика).
 * Справочник заменяется целиком. Официальный набор открытых данных — свой клиент (JDK
 * {@link HttpClient}); файл (~3 МБ) читается целиком с общим сроком на запрос и тело — таймаут
 * запроса JDK покрывает только ожидание заголовков. Сбой — повтор задания.
 */
@Component
public class GeoImportHandler implements TaskHandler {

    /** Тип задания. */
    public static final String TYPE = "GEO_IMPORT";

    private static final String ENTRY = "cities15000.txt";
    /** Версия правил загрузки: другая в {@code geo_import} — справочник загружается заново (§210). */
    public static final String RULES = "a2";

    private static final String ALTERNATES_COUNTRY = "SK";
    /** Население, с которого берутся другие названия города любой страны. */
    static final long ALTERNATES_MIN_POPULATION = 500_000;
    /** Самое короткое другое название города не из Словакии. */
    static final int MIN_ALTERNATE = 4;
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
            repository.replace(cities.values(), RULES);
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
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request,
                HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new IOException("Timeout: " + url, timeout);
        } catch (ExecutionException failed) {
            throw new IOException(failed.getCause());
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
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
    static Map<String, GeoCity> parse(BufferedReader lines) throws IOException {
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
            boolean home = ALTERNATES_COUNTRY.equals(country);
            if (home || population >= ALTERNATES_MIN_POPULATION) {
                for (String alternate : fields[ALTERNATE_NAMES].split(",")) {
                    if (home || alternate.strip().length() >= MIN_ALTERNATE) {
                        add(cities, alternate, country, population);
                    }
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
