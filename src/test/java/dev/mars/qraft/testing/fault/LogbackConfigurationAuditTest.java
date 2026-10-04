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

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that the audit finds every kind of setting that could drop an ERROR event, and nothing in a configuration
 * that has none.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
class LogbackConfigurationAuditTest {

    @Test
    void aConfigurationThatWritesEverythingHasNoFinding() throws Exception {
        assertEquals(List.of(), LogbackConfigurationAudit.settingsThatCanDropErrors(parse("""
                <configuration>
                  <appender name="A" class="ch.qos.logback.core.ConsoleAppender"/>
                  <logger name="noisy" level="WARN"/>
                  <root level="INFO"><appender-ref ref="A"/></root>
                </configuration>
                """)));
    }

    @Test
    void everySettingThatCanDropAnErrorIsFound() throws Exception {
        assertEquals(List.of(
                        "<filter class=\"ch.qos.logback.classic.filter.ThresholdFilter\">",
                        "<turboFilter class=\"ch.qos.logback.classic.turbo.MarkerFilter\">",
                        "<include>",
                        "<neverBlock>true</neverBlock>",
                        "quiet has level OFF",
                        "detached has additivity=\"false\"",
                        "root has level OFF",
                        "the configuration needs exactly one root with at least one appender"),
                LogbackConfigurationAudit.settingsThatCanDropErrors(parse("""
                        <configuration>
                          <turboFilter class="ch.qos.logback.classic.turbo.MarkerFilter"/>
                          <include file="more.xml"/>
                          <appender name="A" class="ch.qos.logback.classic.AsyncAppender">
                            <filter class="ch.qos.logback.classic.filter.ThresholdFilter"/>
                            <neverBlock>true</neverBlock>
                          </appender>
                          <logger name="quiet" level="off"/>
                          <logger name="detached" additivity="false"/>
                          <root level="OFF"/>
                        </configuration>
                        """)));
    }

    @Test
    void aSecondRootIsFound() throws Exception {
        assertEquals(List.of("the configuration needs exactly one root with at least one appender"),
                LogbackConfigurationAudit.settingsThatCanDropErrors(parse("""
                        <configuration>
                          <root><appender-ref ref="A"/></root>
                          <root><appender-ref ref="A"/></root>
                        </configuration>
                        """)));
    }

    @Test
    void aDoctypeIsRefused() {
        assertThrows(Exception.class, () -> parse("<!DOCTYPE configuration><configuration/>"));
    }

    private static Document parse(String configuration) throws Exception {
        return LogbackConfigurationAudit.parse(
                new ByteArrayInputStream(configuration.getBytes(StandardCharsets.UTF_8)));
    }
}
