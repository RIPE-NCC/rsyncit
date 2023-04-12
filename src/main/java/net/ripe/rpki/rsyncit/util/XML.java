package net.ripe.rpki.rsyncit.util;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class XML {
    /**
     * Get an DocumentBuilder that is protected from entity injection.
     * @return new DocumenBuilder
     * @throws ParserConfigurationException when it feels like being peak java
     */
    public static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        final var df = DocumentBuilderFactory.newInstance();
        df.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        df.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return df.newDocumentBuilder();
    }
}
