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

package dev.mars.qraft.common.tls;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link TlsReloader} (certificate reloading list, Phase 2, decisions 2 and 3): a check reloads the material when
 * one of its files has changed and not otherwise, logs a reload at INFO with the certificate's subject and expiry, and
 * logs a reload refused at WARN once for each change of the files, keeping the material in use.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class TlsReloaderTest {
    @TempDir
    Path directory;

    private Path cert;
    private Path key;
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TlsReloader.class);
    private Instant stamp = Instant.parse("2026-11-08T12:00:00Z");

    @BeforeEach
    void capture() {
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void release() {
        logger.detachAppender(log);
    }

    /** Copies {@code fixture} over {@code target} and moves its modification time on, as a rotation would. */
    private void replace(String fixture, Path target) throws Exception {
        Files.copy(TlsMaterialTest.fixture(fixture), target, StandardCopyOption.REPLACE_EXISTING);
        stamp = stamp.plusSeconds(1);
        Files.setLastModifiedTime(target, FileTime.from(stamp));
    }

    private List<String> logged(Level level) {
        return log.list.stream().filter(event -> event.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void aChangedFileIsReloadedAndARefusedReloadIsLoggedOncePerChange() throws Exception {
        Path ca = directory.resolve("ca.pem");
        cert = directory.resolve("cert.pem");
        key = directory.resolve("cert.key");
        replace("ca.pem", ca);
        replace("server.pem", cert);
        replace("server.key", key);
        TlsMaterial material = TlsMaterial.load(new TlsSettings("server.tls", ca, Optional.of(cert), Optional.of(key),
                List.of("TLSv1.3", "TLSv1.2")));
        try (TlsReloader reloader = TlsReloader.start(material, Duration.ofHours(1))) {
            reloader.check();
            assertEquals("RSA", material.certificate().orElseThrow().getPublicKey().getAlgorithm());
            assertTrue(logged(Level.INFO).isEmpty(), "nothing changed, so nothing was reloaded: " + logged(Level.INFO));

            replace("server-ec.pem", cert);
            reloader.check();
            assertEquals("RSA", material.certificate().orElseThrow().getPublicKey().getAlgorithm(),
                    "a certificate without its key is refused");
            reloader.check();
            List<String> warned = logged(Level.WARN);
            assertEquals(1, warned.size(), "once for the change: " + warned);
            assertTrue(warned.getFirst().contains("server.tls was not reloaded")
                    && warned.getFirst().contains("is not the key of"), warned.getFirst());

            replace("server-ec.key", key);
            reloader.check();
            assertEquals("EC", material.certificate().orElseThrow().getPublicKey().getAlgorithm());
            List<String> reloaded = logged(Level.INFO);
            assertEquals(1, reloaded.size(), reloaded.toString());
            assertTrue(reloaded.getFirst().contains("server.tls reloaded")
                    && reloaded.getFirst().contains(material.certificate().orElseThrow().getSubjectX500Principal()
                    .getName()) && reloaded.getFirst().contains("valid until"), reloaded.getFirst());
            reloader.check();
            assertEquals(1, logged(Level.INFO).size(), "and not again while nothing changes");
        }
    }
}
