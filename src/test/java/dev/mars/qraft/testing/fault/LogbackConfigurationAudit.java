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

package dev.mars.qraft.testing.fault;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the settings in a Logback configuration that could stop an ERROR event from being written: a filter or
 * turbo filter, an included file this audit cannot see, an asynchronous appender that discards events when its
 * queue is full, a level of {@code OFF}, a logger that does not pass events to the root, or a root without an
 * appender. Used for both the production and the test configuration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
public final class LogbackConfigurationAudit {

    private LogbackConfigurationAudit() {
    }

    /** Parses a Logback configuration document, refusing a DOCTYPE. A malformed document throws; nothing is printed. */
    public static Document parse(InputStream configuration) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        return builder.parse(configuration);
    }

    /** Every setting in {@code configuration} that could drop an ERROR event; empty when there is none. */
    public static List<String> settingsThatCanDropErrors(Document configuration) {
        List<String> found = new ArrayList<>();
        for (String element : List.of("filter", "turboFilter", "include")) {
            for (Element setting : elements(configuration, element)) {
                found.add("<" + element + (setting.hasAttribute("class")
                        ? " class=\"" + setting.getAttribute("class") + "\">" : ">"));
            }
        }
        for (Element neverBlock : elements(configuration, "neverBlock")) {
            if ("true".equalsIgnoreCase(neverBlock.getTextContent().trim())) found.add("<neverBlock>true</neverBlock>");
        }
        for (String element : List.of("logger", "root")) {
            for (Element logger : elements(configuration, element)) {
                String name = element.equals("root") ? "root" : logger.getAttribute("name");
                if ("OFF".equalsIgnoreCase(logger.getAttribute("level").trim())) {
                    found.add(name + " has level OFF");
                }
                if ("false".equalsIgnoreCase(logger.getAttribute("additivity").trim())) {
                    found.add(name + " has additivity=\"false\"");
                }
            }
        }
        List<Element> roots = elements(configuration, "root");
        if (roots.size() != 1 || roots.getFirst().getElementsByTagName("appender-ref").getLength() == 0) {
            found.add("the configuration needs exactly one root with at least one appender");
        }
        return found;
    }

    private static List<Element> elements(Document document, String name) {
        NodeList nodes = document.getElementsByTagName(name);
        List<Element> elements = new ArrayList<>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            elements.add((Element) nodes.item(index));
        }
        return elements;
    }
}
