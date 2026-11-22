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

package dev.mars.qraft.runtime;

import dev.mars.qraft.raft.SharedDockerClusterFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shaded runtime JAR carries Qraft's own licence and notice, and keeps the notices of the dependencies it
 * repackages, which the Apache License, section 4(d), asks a redistribution to carry. Run after {@code mvn install}, as
 * the end-to-end stage is, against the {@code target/qraft.jar} that build packaged.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-22
 * @version 1.0
 */
@Tag("e2e")
class PackagedLicenceEndToEndTest {
    private static final Path REPOSITORY = Path.of("").toAbsolutePath();
    private static final Path JAR = REPOSITORY.resolve("target").resolve("qraft.jar");

    @BeforeEach
    void theJarIsTheOneThisBuildPackaged() {
        SharedDockerClusterFixture.assertRuntimeJarIsCurrent(REPOSITORY, JAR);
    }

    @Test
    void theJarsLicenseIsQraftsLicence() throws IOException {
        assertArrayEquals(Files.readAllBytes(REPOSITORY.resolve("LICENSE")), packaged("META-INF/LICENSE"),
                "META-INF/LICENSE is not Qraft's LICENSE");
    }

    @Test
    void theJarsNoticeHoldsQraftsNoticeAndTheNoticesOfWhatItRepackages() throws IOException {
        String notice = text(packaged("META-INF/NOTICE"));

        assertTrue(notice.contains(text(Files.readAllBytes(REPOSITORY.resolve("NOTICE"))).strip()), notice);
        assertTrue(notice.contains("Jackson is a high-performance, Free/Open Source JSON processing library."),
                notice);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static byte[] packaged(String name) throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            var entry = jar.getJarEntry(name);
            assertNotNull(entry, name + " is not in " + JAR);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }
}
