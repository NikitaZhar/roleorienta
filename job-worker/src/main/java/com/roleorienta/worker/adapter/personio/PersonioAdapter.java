package com.roleorienta.worker.adapter.personio;

import com.roleorienta.worker.adapter.SourceAdapter;
import com.roleorienta.worker.adapter.SourceReadResult;
import com.roleorienta.worker.http.ExternalHttpClient;
import com.roleorienta.worker.http.HttpResult;
import com.roleorienta.worker.vacancy.FetchedPosting;
import com.roleorienta.worker.xml.SafeXml;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * Адаптер Personio: публичная XML-лента витрины {@code https://<доска>/xml}, весь список одним
 * ответом. Доска — хост витрины: {@code <компания>.jobs.personio.de} или
 * {@code <компания>.jobs.personio.com}; другой хост не читается (отказ {@code BLOCKED}). Корень {@code workzag-jobs}; у {@code position} — {@code id},
 * {@code name}, {@code office}, тексты {@code jobDescriptions/jobDescription/value}. Ссылка на
 * публикацию — {@code /job/<id>}. https://developer.personio.de/docs/retrieving-open-job-positions
 */
@Component
public class PersonioAdapter implements SourceAdapter {

    /** Код провайдера. */
    public static final String PROVIDER = "personio";

    private static final String ROOT = "workzag-jobs";

    private static final Pattern BOARD_HOST = Pattern.compile("[a-z0-9-]+\\.jobs\\.personio\\.(de|com)");

    private final ExternalHttpClient httpClient;
    private final PersonioProperties properties;

    /**
     * @param httpClient внешний HTTP-клиент
     * @param properties адрес витрины
     */
    public PersonioAdapter(ExternalHttpClient httpClient, PersonioProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SourceReadResult read(String board) {
        if (!BOARD_HOST.matcher(board).matches()) {
            return new SourceReadResult.Unavailable(new HttpResult.PermanentFailure(
                    HttpResult.Kind.BLOCKED, "Not a Personio board host: " + board));
        }
        String base = properties.baseUrlTemplate().replace("{board}", board);
        HttpResult result = httpClient.get(URI.create(base + "/xml"));
        if (!(result instanceof HttpResult.Success success)) {
            return new SourceReadResult.Unavailable(result);
        }
        try {
            return SourceReadResult.Read.full(parse(success.body(), base), List.of(success.body()));
        } catch (XMLStreamException exception) {
            return new SourceReadResult.Unavailable(new HttpResult.TemporaryFailure(
                    "Malformed Personio feed: " + exception.getMessage(), Duration.ZERO));
        }
    }

    private static List<FetchedPosting> parse(String body, String base) throws XMLStreamException {
        XMLStreamReader xml = SafeXml.reader(body);
        try {
            if (xml.nextTag() != XMLStreamConstants.START_ELEMENT || !ROOT.equals(xml.getLocalName())) {
                throw new XMLStreamException("Root element is not " + ROOT);
            }
            List<FetchedPosting> postings = new ArrayList<>();
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && "position".equals(xml.getLocalName())) {
                    FetchedPosting posting = position(xml, base);
                    if (posting != null) {
                        postings.add(posting);
                    }
                }
            }
            return postings;
        } finally {
            xml.close();
        }
    }

    /**
     * Читает один {@code position} до его закрывающего тега; прямые поля — на глубине 0, тексты
     * описания — {@code value} внутри {@code jobDescription} (глубина 2).
     */
    private static FetchedPosting position(XMLStreamReader xml, String base) throws XMLStreamException {
        String id = null;
        String title = null;
        String office = null;
        StringJoiner content = new StringJoiner("\n");
        int depth = 0;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                String name = xml.getLocalName();
                if (depth == 0 && "id".equals(name)) {
                    id = xml.getElementText().trim();
                } else if (depth == 0 && "name".equals(name)) {
                    title = xml.getElementText().trim();
                } else if (depth == 0 && "office".equals(name)) {
                    office = xml.getElementText().trim();
                } else if (depth == 2 && "value".equals(name)) {
                    content.add(xml.getElementText());
                } else {
                    depth++;
                }
            }
        }
        if (id == null || id.isEmpty() || title == null || title.isEmpty()) {
            return null;
        }
        return new FetchedPosting(id, title, base + "/job/" + id, office,
                content.length() == 0 ? null : content.toString());
    }
}
