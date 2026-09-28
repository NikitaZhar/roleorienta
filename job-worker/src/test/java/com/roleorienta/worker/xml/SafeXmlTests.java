package com.roleorienta.worker.xml;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;

/**
 * Защита разборщика: внешние сущности и чрезмерная вложенность отвергаются.
 */
class SafeXmlTests {

    /**
     * Внешняя сущность (XXE) не раскрывается — разбор падает.
     */
    @Test
    void rejectsExternalEntity() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <root>&xxe;</root>
                """;

        assertThatThrownBy(() -> readAll(xml)).isInstanceOf(XMLStreamException.class);
    }

    /**
     * Вложенность больше {@link SafeXml#MAX_DEPTH} — разбор падает.
     */
    @Test
    void rejectsDeepNesting() {
        String xml = "<a>".repeat(SafeXml.MAX_DEPTH + 1) + "</a>".repeat(SafeXml.MAX_DEPTH + 1);

        assertThatThrownBy(() -> readAll(xml)).isInstanceOf(XMLStreamException.class);
    }

    private static void readAll(String xml) throws XMLStreamException {
        XMLStreamReader reader = SafeXml.reader(xml);
        while (reader.hasNext()) {
            reader.next();
        }
    }
}
