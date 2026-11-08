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

package dev.mars.qraft.server.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@code server.audit} (authentication list, Phase 3, decision 5): the audit file's path, absent by default, and
 * nothing else in the block.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class AppConfigAuditTest {
    @Test
    void theAuditFileIsOptionalAndTheBlockTakesOnlyItsPath() {
        assertEquals(Optional.empty(), AppConfig.fromJson("{\"version\":1,\"server\":{}}").getAuditPath());
        assertEquals(Optional.of(Path.of("audit", "qraft-audit.json")), AppConfig.fromJson(
                "{\"version\":1,\"server\":{\"audit\":{\"path\":\"audit/qraft-audit.json\"}}}").getAuditPath());
        assertThrows(IllegalArgumentException.class, () -> AppConfig.fromJson(
                "{\"version\":1,\"server\":{\"audit\":{\"path\":\"a\",\"format\":\"json\"}}}"));
    }
}
