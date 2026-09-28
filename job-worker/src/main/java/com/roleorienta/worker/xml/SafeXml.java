package com.roleorienta.worker.xml;

import java.io.StringReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;

/**
 * Единственный разборщик внешнего XML (технический документ §10, XXE): StAX из JDK без DTD и
 * внешних сущностей, с потолком вложенности. Размер ограничен потолком тела ответа
 * {@code app.http.max-body-bytes}, время — размером.
 * https://owasp.org/www-community/vulnerabilities/XML_External_Entity_(XXE)_Processing
 */
public final class SafeXml {

    /** Потолок вложенности элементов. */
    public static final int MAX_DEPTH = 32;

    private static final XMLInputFactory FACTORY = createFactory();

    private SafeXml() {
    }

    /**
     * @param xml документ
     * @return потоковый читатель; при превышении вложенности {@code next()} бросает
     *         {@link XMLStreamException}
     * @throws XMLStreamException документ не читается
     */
    public static XMLStreamReader reader(String xml) throws XMLStreamException {
        return new DepthLimitedReader(FACTORY.createXMLStreamReader(new StringReader(xml)));
    }

    private static XMLInputFactory createFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /**
     * Считает вложенность элементов. {@code nextTag} и {@code getElementText} переопределены, чтобы
     * счёт шёл через {@link #next()} и закрывающий тег, прочитанный вместе с текстом.
     */
    private static final class DepthLimitedReader extends StreamReaderDelegate {

        private int depth;

        DepthLimitedReader(XMLStreamReader reader) {
            super(reader);
        }

        @Override
        public int next() throws XMLStreamException {
            int event = super.next();
            if (event == XMLStreamConstants.START_ELEMENT && ++depth > MAX_DEPTH) {
                throw new XMLStreamException("XML nesting deeper than " + MAX_DEPTH);
            }
            if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
            return event;
        }

        @Override
        public int nextTag() throws XMLStreamException {
            int event = next();
            while (event == XMLStreamConstants.CHARACTERS && isWhiteSpace()
                    || event == XMLStreamConstants.SPACE || event == XMLStreamConstants.COMMENT
                    || event == XMLStreamConstants.PROCESSING_INSTRUCTION || event == XMLStreamConstants.DTD) {
                event = next();
            }
            if (event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.END_ELEMENT) {
                throw new XMLStreamException("Expected start or end tag");
            }
            return event;
        }

        @Override
        public String getElementText() throws XMLStreamException {
            String text = super.getElementText();
            depth--;
            return text;
        }
    }
}
