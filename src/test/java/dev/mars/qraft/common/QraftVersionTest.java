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

package dev.mars.qraft.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests {@link QraftVersion}: the version is the one the build wrote into the jar's manifest, and a build that
 * does not run from the jar says so. {@code RuntimeLoggingTest} shows both modes reporting it, from the class
 * path and from the packaged jar.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
class QraftVersionTest {

    @Test
    void theVersionIsTheOneTheBuildWroteIntoTheManifest() {
        assertEquals("1.0-SNAPSHOT", QraftVersion.from("1.0-SNAPSHOT"));
        assertEquals("2.3.1", QraftVersion.from("  2.3.1 "), "surrounding white space is not part of a version");
    }

    @Test
    void aBuildWithoutAManifestEntryReportsDevelopment() {
        assertEquals("development", QraftVersion.DEVELOPMENT);
        assertEquals(QraftVersion.DEVELOPMENT, QraftVersion.from(null));
        assertEquals(QraftVersion.DEVELOPMENT, QraftVersion.from(""));
        assertEquals(QraftVersion.DEVELOPMENT, QraftVersion.from("   "));
    }

    @Test
    void aTestRunDoesNotComeFromTheJarAndSoReportsDevelopment() {
        assertEquals(QraftVersion.DEVELOPMENT, QraftVersion.current());
    }
}
