package com.roleorienta.worker.xml;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Фабрики XML-парсеров с защитой от XXE (§9, A14).
 *
 * <p>SSRF-фильтр HTTP не покрывает загрузку внешних сущностей XML-парсером, поэтому
 * для любых XML-лент (например, Personio) разбор обязан идти через эти фабрики:
 * запрет DTD и внешних сущностей, запрет внешнего разрешения схем, отключение
 * раскрытия сущностей. Это требование <b>пилота</b> при выборе XML, а не позднее
 * усиление.</p>
 *
 * <p>Пока в проекте нет XML-адаптера — утилита введена заранее, чтобы безопасный
 * путь существовал раньше первого небезопасного вызывающего. Правило: разбор
 * XML-ленты идёт только через {@link #hardenedStaxFactory()} /
 * {@link #hardenedDomFactory()}.</p>
 */
public final class SafeXml {

    private SafeXml() {
    }

    /**
     * StAX-фабрика с запретом DTD и внешних сущностей.
     */
    public static XMLInputFactory hardenedStaxFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // Полный запрет DTD: и точка входа XXE, и вектор entity-expansion.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /**
     * DOM-фабрика с запретом DOCTYPE, внешних общих/параметрических сущностей,
     * загрузки внешних DTD и XInclude.
     */
    public static DocumentBuilderFactory hardenedDomFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Самая надёжная мера: полностью запретить DOCTYPE.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        return factory;
    }

    /**
     * Разбирает XML-документ целиком (DOM) парсером {@link #hardenedDomFactory()}: DOCTYPE и
     * внешние сущности запрещены. Для небольших лент (Personio, §91), где удобнее дерево, чем поток.
     *
     * @param xml текст документа
     * @return документ
     * @throws IllegalStateException если документ не разбирается (в том числе при DOCTYPE)
     */
    public static Document parseDom(String xml) {
        try {
            DocumentBuilder builder = hardenedDomFactory().newDocumentBuilder();
            // Без обработчика парсер печатает ошибки в stderr; DefaultHandler молчит, фатальные — исключением.
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new IllegalStateException("XML не разобран: " + exception.getMessage(), exception);
        }
    }
}
