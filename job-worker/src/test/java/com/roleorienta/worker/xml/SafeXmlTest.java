package com.roleorienta.worker.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** Защита от XXE (§9, A14): DOCTYPE/внешние сущности не разбираются. */
class SafeXmlTest {

    private static final String XXE_PAYLOAD =
            "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/hostname\"> ]>"
            + "<foo>&xxe;</foo>";

    private ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void domFactoryRejectsDoctype() throws Exception {
        DocumentBuilder builder = SafeXml.hardenedDomFactory().newDocumentBuilder();
        assertThrows(SAXException.class, () -> builder.parse(stream(XXE_PAYLOAD)));
    }

    @Test
    void domFactoryParsesPlainXml() throws Exception {
        DocumentBuilder builder = SafeXml.hardenedDomFactory().newDocumentBuilder();
        Document doc = builder.parse(stream("<foo>hello</foo>"));
        assertEquals("hello", doc.getDocumentElement().getTextContent());
    }

    @Test
    void staxFactoryRejectsDoctype() throws Exception {
        XMLInputFactory factory = SafeXml.hardenedStaxFactory();
        XMLStreamReader reader = factory.createXMLStreamReader(stream(XXE_PAYLOAD));
        assertThrows(XMLStreamException.class, () -> {
            while (reader.hasNext()) {
                reader.next();
            }
        });
    }
}
