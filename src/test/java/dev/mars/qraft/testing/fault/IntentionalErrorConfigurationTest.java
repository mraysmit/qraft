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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static dev.mars.qraft.testing.fault.IntentionalError.SELF_TEST_INJECTED_FAILURE;
import static dev.mars.qraft.testing.fault.IntentionalError.SELF_TEST_INTENTIONAL_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the test run is wired as the labelling requires: {@code config/logback-test.xml} cannot drop an error,
 * every appender that writes puts the label right after the level, the check is attached to the root logger, the
 * extension opens a window for every test, and an intentional error and an injected failure reach the log file with
 * their labels.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
class IntentionalErrorConfigurationTest {

    private static final String SELF_TEST_LOGGER = "dev.mars.qraft.testing.fault.selftest";

    @Test
    void theTestConfigurationCannotDropAnError() throws Exception {
        String location = System.getProperty("logback.configurationFile");
        assertNotNull(location, "logback.configurationFile must name config/logback-test.xml");

        try (InputStream input = Files.newInputStream(Path.of(location))) {
            assertEquals(List.of(), LogbackConfigurationAudit.settingsThatCanDropErrors(
                    LogbackConfigurationAudit.parse(input)));
        }
    }

    @Test
    void everyAppenderThatWritesPutsTheLabelAfterTheLevel() {
        List<String> writers = new ArrayList<>();
        for (Iterator<Appender<ILoggingEvent>> appenders = root().iteratorForAppenders(); appenders.hasNext(); ) {
            if (appenders.next() instanceof OutputStreamAppender<ILoggingEvent> writer) {
                writers.add(writer.getName());
                assertTrue(writer.getEncoder() instanceof PatternLayoutEncoder encoder
                                && encoder.getPattern().contains("%-5level %intentional%logger"),
                        writer.getName() + " must write %-5level %intentional%logger");
            }
        }

        assertEquals(List.of("CONSOLE", "FILE"), writers);
        IntentionalErrorCheck.requireAttachedTo(root());
    }

    @Test
    void theExtensionOpensAWindowForEveryTest() {
        assertEquals(Optional.of("IntentionalErrorConfigurationTest#theExtensionOpensAWindowForEveryTest"),
                IntentionalErrors.openWindow());
    }

    @Test
    void anIntentionalErrorIsWrittenToTheLogWithItsLabel() throws IOException {
        IntentionalErrors.expect(SELF_TEST_INTENTIONAL_ERROR, 1);
        String message = "Self-test intentional error " + System.nanoTime();

        String line = logAndReadBack(message, null);

        assertTrue(line.contains("ERROR *** INTENTIONAL ERROR: SELF_TEST_INTENTIONAL_ERROR, caused by"
                + " IntentionalErrorConfigurationTest#anIntentionalErrorIsWrittenToTheLogWithItsLabel *** "
                + SELF_TEST_LOGGER), line);
    }

    @Test
    void anInjectedFailureIsWrittenToTheLogWithItsLabel() throws IOException {
        IntentionalErrors.expect(SELF_TEST_INJECTED_FAILURE, 1);
        String message = "Self-test injected failure " + System.nanoTime();

        String line = logAndReadBack(message, new InjectedFault(SELF_TEST_INJECTED_FAILURE, "simulated"));

        assertTrue(line.contains("ERROR *** INJECTED FAILURE: SELF_TEST_INJECTED_FAILURE, injected by"
                + " IntentionalErrorConfigurationTest#anInjectedFailureIsWrittenToTheLogWithItsLabel *** "
                + SELF_TEST_LOGGER), line);
    }

    /** Logs {@code message} at ERROR and returns the line of the log file that holds it. */
    private static String logAndReadBack(String message, Throwable failure) throws IOException {
        Path file = logFile();
        long start = Files.size(file);
        LoggerFactory.getLogger(SELF_TEST_LOGGER).error(message, failure);

        byte[] written;
        try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
            reader.seek(start);
            written = new byte[Math.toIntExact(reader.length() - start)];
            reader.readFully(written);
        }
        return new String(written, StandardCharsets.UTF_8).lines()
                .filter(line -> line.endsWith(" - " + message))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the log file " + file + " has no line ending with " + message));
    }

    private static Path logFile() {
        for (Iterator<Appender<ILoggingEvent>> appenders = root().iteratorForAppenders(); appenders.hasNext(); ) {
            if (appenders.next() instanceof FileAppender<ILoggingEvent> file) return Path.of(file.getFile());
        }
        throw new AssertionError("the root logger has no file appender");
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
