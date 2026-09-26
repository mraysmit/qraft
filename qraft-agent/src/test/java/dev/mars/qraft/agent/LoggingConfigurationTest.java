/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.qraft.agent;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that the packaged agent logback.xml references loadable classes, encodes as UTF-8, sets the
 * log directory, and declares a JSON appender but no OTEL appender.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-10
 * @version 1.0
 */
class LoggingConfigurationTest {

    @Test
    void productionConfigurationUsesAvailableComponentsAndUtf8() throws Exception {
        Document configuration = loadConfiguration();

        assertClassAvailable(configuration.getElementsByTagName("statusListener"));
        assertClassAvailable(configuration.getElementsByTagName("appender"));
        assertClassAvailable(configuration.getElementsByTagName("encoder"));

        NodeList encoders = configuration.getElementsByTagName("encoder");
        for (int index = 0; index < encoders.getLength(); index++) {
            Element encoder = (Element) encoders.item(index);
            if (!encoder.hasAttribute("class")) {
                assertEquals("UTF-8", encoder.getElementsByTagName("charset").item(0).getTextContent());
            }
        }

        Element logDirectory = findProperty(configuration, "LOG_DIR");
        assertEquals("${qraft.log.dir:-${user.dir}/logs}", logDirectory.getAttribute("value"));
        assertNotNull(findAppender(configuration, "JSON"));
        assertNull(findAppender(configuration, "OTEL"),
                "agent must not declare telemetry logging until it configures an OpenTelemetry SDK");
    }

    private static Document loadConfiguration() throws Exception {
        try (InputStream input = LoggingConfigurationTest.class.getClassLoader().getResourceAsStream("logback.xml")) {
            assertNotNull(input, "production logback.xml must be packaged");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static void assertClassAvailable(NodeList elements) throws ClassNotFoundException {
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (element.hasAttribute("class")) {
                assertNotNull(Class.forName(element.getAttribute("class")));
            }
        }
    }

    private static Element findProperty(Document document, String name) {
        NodeList properties = document.getElementsByTagName("property");
        for (int index = 0; index < properties.getLength(); index++) {
            Element property = (Element) properties.item(index);
            if (name.equals(property.getAttribute("name"))) {
                return property;
            }
        }
        return null;
    }

    private static Element findAppender(Document document, String name) {
        NodeList appenders = document.getElementsByTagName("appender");
        for (int index = 0; index < appenders.getLength(); index++) {
            Element appender = (Element) appenders.item(index);
            if (name.equals(appender.getAttribute("name"))) {
                return appender;
            }
        }
        return null;
    }
}
